package com.republicate.kroom.view

/**
 * Linux ViewHandler implementation (headless).
 *
 * Linux native targets don't have a standard WebView component.
 * Use JVM target for desktop with WebView needs, or run the webapp in the system browser.
 */
actual object ViewHandler {

    actual fun init() {
        // Headless mode - no UI
    }

    actual fun serve(path: String): ByteArray {
        throw UnsupportedOperationException("ViewHandler.serve not available on headless native targets")
    }
}
