package com.republicate.kroom.view

/**
 * Platform-specific handler for serving webapp resources.
 *
 * This abstraction allows kroom webapps to serve their HTML/JS/CSS assets
 * from different sources depending on the platform:
 * - JVM/Android: bundled resources with optional Velocity templating
 * - JS/Browser: not needed (browser handles resources natively)
 * - Native: headless only, no resource serving
 * - iOS/macOS: WKWebView integration (future)
 */
expect object ViewHandler {
    /**
     * Start the template engine now. Optional: where there is one, the first
     * template served starts it, and a static file never does.
     */
    fun init()

    /**
     * Serve a resource at the given path.
     * @param path the resource path (e.g., "index", "js/app.js", "css/style.css")
     * @return the resource content as bytes
     */
    fun serve(path: String): ByteArray
}
