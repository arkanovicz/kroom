package com.republicate.kroom.view

import kotlinx.cinterop.*
import platform.Foundation.*

/**
 * iOS ViewHandler implementation.
 *
 * Serves webapp resources from the app bundle.
 * Resources should be placed in the app's bundle under a "webapp" directory.
 */
@OptIn(ExperimentalForeignApi::class)
actual object ViewHandler {
    private var initialized = false
    private var bundle: NSBundle? = null

    /**
     * Set the bundle to load resources from.
     * Defaults to main bundle if not set.
     */
    fun setBundle(bundle: NSBundle) {
        this.bundle = bundle
    }

    actual fun init() {
        if (initialized) return
        if (bundle == null) {
            bundle = NSBundle.mainBundle
        }
        initialized = true
    }

    actual fun serve(path: String): ByteArray {
        if (!initialized) init()

        val cleanPath = path.removePrefix("/")
        val segments = cleanPath.split('/')
        val file = segments.last()

        return if (file.contains('.')) {
            // Static asset
            loadResource("webapp/$cleanPath")
                ?: throw IllegalArgumentException("Resource not found: $cleanPath")
        } else {
            // Template/page
            loadResource("webapp/templates/$cleanPath.html")
                ?: loadResource("webapp/$cleanPath.html")
                ?: throw IllegalArgumentException("Template not found: $cleanPath")
        }
    }

    private fun loadResource(path: String): ByteArray? {
        val currentBundle = bundle ?: NSBundle.mainBundle

        // Split path into directory and filename
        val lastSlash = path.lastIndexOf('/')
        val (directory, filename) = if (lastSlash >= 0) {
            path.substring(0, lastSlash) to path.substring(lastSlash + 1)
        } else {
            null to path
        }

        // Split filename into name and extension
        val lastDot = filename.lastIndexOf('.')
        val (name, ext) = if (lastDot >= 0) {
            filename.substring(0, lastDot) to filename.substring(lastDot + 1)
        } else {
            filename to null
        }

        // Find resource in bundle
        val resourcePath = currentBundle.pathForResource(name, ext, directory)
            ?: return null

        // Load data
        val data = NSData.dataWithContentsOfFile(resourcePath)
            ?: return null

        return data.toByteArray()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun NSData.toByteArray(): ByteArray {
        val size = length.toInt()
        if (size == 0) return ByteArray(0)

        return ByteArray(size).apply {
            usePinned { pinned ->
                memcpy(pinned.addressOf(0), bytes, length)
            }
        }
    }
}
