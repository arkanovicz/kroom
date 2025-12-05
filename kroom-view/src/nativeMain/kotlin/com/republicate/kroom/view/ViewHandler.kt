package com.republicate.kroom.view

/**
 * Native (Linux/Windows/etc.) ViewHandler implementation.
 *
 * For non-Apple native targets, there's no standard WebView component.
 * This is a headless-only stub. Use JVM target for desktop with WebView needs,
 * or run the webapp in the system browser.
 */
actual object ViewHandler {

    actual fun init() {
        // Headless mode - no UI
    }

    actual fun serve(path: String): ByteArray {
        throw UnsupportedOperationException("ViewHandler.serve not available on headless native targets")
    }
}
