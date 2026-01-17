package com.republicate.kroom.webapp.core

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches file versions (lastModified timestamps) for static resources.
 * Used by Velocity templates to add cache-busting query parameters.
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
        if (staticDir == null) return "0"

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
                val file = File(staticDir, normalizedPath)
                val lastModified = try {
                    if (file.isFile) Files.getLastModifiedTime(file.toPath()).toMillis()
                    else oldValue?.first ?: 0L
                } catch (_: Exception) {
                    oldValue?.first ?: 0L
                }
                lastModified to now
            }
        }!!

        return entry.first.toString()
    }
}
