package com.republicate.kroom.view

/**
 * macOS ViewHandler implementation (headless stub).
 *
 * macOS could use WKWebView similar to iOS, but typically desktop apps
 * run the webapp in a system browser. This stub is provided for library completion.
 */
actual object ViewHandler {

    actual fun init() {
        // Headless mode - no UI
    }

    actual fun serve(path: String): ByteArray {
        throw UnsupportedOperationException("ViewHandler.serve not available on macOS native target - use JVM or system browser")
    }
}
