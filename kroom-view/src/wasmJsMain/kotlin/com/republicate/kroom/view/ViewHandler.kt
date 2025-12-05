package com.republicate.kroom.view

/**
 * WASM/JS ViewHandler implementation.
 *
 * In WASM browser context, asset serving is handled natively by the browser.
 * This implementation is a no-op since there's no need to intercept resources.
 */
actual object ViewHandler {

    actual fun init() {
        // No-op in browser - assets served by web server or bundler
    }

    actual fun serve(path: String): ByteArray {
        throw UnsupportedOperationException("ViewHandler.serve not applicable in browser context")
    }
}
