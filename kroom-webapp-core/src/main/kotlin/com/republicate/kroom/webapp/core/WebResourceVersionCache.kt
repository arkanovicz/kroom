package com.republicate.kroom.webapp.core

import java.io.File
import java.net.JarURLConnection
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches file versions (lastModified timestamps) for static resources.
 * Used by Velocity templates to add cache-busting query parameters.
 *
 * Checks both filesystem (for dev mode hot reload) and classpath (for jar resources).
 *
 * Usage in Velocity: $versions.get("/js/app.js") returns timestamp string
 * Combined with #versioned macro: #versioned("/js/app.js") → /js/app.js?v=123456789
 */
class WebResourceVersionCache(
    private val staticDir: File?,
    private val refreshRate: Long = 300_000L // 5 min
) {
    // cache[path] = (lastModified, lastChecked)
    private val cache = ConcurrentHashMap<String, Pair<Long, Long>>()

    /**
     * Get version string for a resource path.
     * Returns lastModified timestamp as string, or "0" if file not found.
     */
    fun get(path: String): String {
        val now = System.currentTimeMillis()

        // Fast path: fresh enough
        cache[path]?.let { (lastModified, lastChecked) ->
            if (now - lastChecked < refreshRate) return lastModified.toString()
        }

        // Slow path: revalidate (per-key)
        val entry = cache.compute(path) { _, oldValue ->
            // Re-check under compute to avoid races
            if (oldValue != null && now - oldValue.second < refreshRate) {
                oldValue
            } else {
                val normalizedPath = if (path.startsWith("/")) path.substring(1) else path
                val lastModified = getFileVersion(normalizedPath) ?: oldValue?.first ?: 0L
                lastModified to now
            }
        }!!

        return entry.first.toString()
    }

    /**
     * Get file version from filesystem or classpath.
     * Returns lastModified timestamp or null if not found.
     */
    private fun getFileVersion(path: String): Long? {
        // Try filesystem first (for dev mode hot reload)
        if (staticDir != null) {
            val file = File(staticDir, path)
            if (file.isFile) {
                return try {
                    Files.getLastModifiedTime(file.toPath()).toMillis()
                } catch (_: Exception) {
                    null
                }
            }
        }

        // Fall back to classpath (for jar resources)
        val resourcePath = "static/$path"
        val url = Thread.currentThread().contextClassLoader.getResource(resourcePath)
        if (url != null) {
            return try {
                when (url.protocol) {
                    "file" -> File(url.toURI()).lastModified()
                    "jar" -> (url.openConnection() as JarURLConnection).jarEntry.lastModifiedTime.toMillis()
                    else -> System.currentTimeMillis() // Unknown protocol, use current time
                }
            } catch (_: Exception) {
                System.currentTimeMillis() // Fallback to current time
            }
        }

        return null
    }
}
