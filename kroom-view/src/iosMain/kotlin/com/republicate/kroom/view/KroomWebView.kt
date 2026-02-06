package com.republicate.kroom.view

import kotlinx.cinterop.*
import kotlin.experimental.ExperimentalObjCRefinement
import platform.Foundation.*
import platform.WebKit.*
import platform.darwin.NSObject

/**
 * URL scheme handler for serving local webapp resources via WKWebView.
 *
 * Intercepts requests with the "kroom" scheme and serves content from ViewHandler.
 * API and SSE requests (starting with /api or /events) are passed through to the server.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalObjCRefinement::class)
class KroomURLSchemeHandler(
    private val passthroughPrefixes: List<String> = listOf("/api", "/events")
) : NSObject(), WKURLSchemeHandlerProtocol {

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, startURLSchemeTask: WKURLSchemeTaskProtocol) {
        val request = startURLSchemeTask.request
        val url = request.URL ?: run {
            startURLSchemeTask.didFailWithError(
                NSError.errorWithDomain("KroomError", code = 400, userInfo = null)
            )
            return
        }

        val path = url.path ?: "/"

        // Check if this should be passed through (shouldn't happen with scheme handler, but just in case)
        for (prefix in passthroughPrefixes) {
            if (path.startsWith(prefix)) {
                startURLSchemeTask.didFailWithError(
                    NSError.errorWithDomain("KroomError", code = 404, userInfo = mapOf(
                        NSLocalizedDescriptionKey to "Passthrough paths should not use kroom:// scheme"
                    ))
                )
                return
            }
        }

        try {
            val resourcePath = if (path == "/" || path.isEmpty()) "index" else path.removePrefix("/")
            val content = ViewHandler.serve(resourcePath)
            val mimeType = getMimeType(resourcePath)

            val response = NSHTTPURLResponse(
                uRL = url,
                statusCode = 200,
                HTTPVersion = "HTTP/1.1",
                headerFields = mapOf(
                    "Content-Type" to "$mimeType; charset=utf-8",
                    "Content-Length" to content.size.toString()
                )
            )

            startURLSchemeTask.didReceiveResponse(response!!)
            startURLSchemeTask.didReceiveData(content.toNSData())
            startURLSchemeTask.didFinish()
        } catch (e: Exception) {
            val errorResponse = NSHTTPURLResponse(
                uRL = url,
                statusCode = 404,
                HTTPVersion = "HTTP/1.1",
                headerFields = mapOf("Content-Type" to "text/plain")
            )
            val errorContent = "Resource not found: $path".encodeToByteArray()

            startURLSchemeTask.didReceiveResponse(errorResponse!!)
            startURLSchemeTask.didReceiveData(errorContent.toNSData())
            startURLSchemeTask.didFinish()
        }
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, stopURLSchemeTask: WKURLSchemeTaskProtocol) {
        // Called when the task is cancelled - nothing to clean up
    }

    private fun getMimeType(path: String): String {
        val ext = path.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "eot" -> "application/vnd.ms-fontobject"
            else -> "application/octet-stream"
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun ByteArray.toNSData(): NSData {
        if (isEmpty()) return NSData()
        return usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), size.toULong())
        }
    }
}

/**
 * Configuration for KroomWebView.
 */
class KroomWebViewConfig {
    /**
     * Base URL for the API server.
     * Example: "https://api.example.com" or "http://10.0.2.2:8080" for local dev
     */
    var apiBaseUrl: String = ""

    /**
     * URL prefixes that should pass through to the API server instead of being served locally.
     */
    var passthroughPrefixes: List<String> = listOf("/api", "/events")

    /**
     * Enable JavaScript in the WebView.
     */
    var javaScriptEnabled: Boolean = true

    /**
     * Enable DOM storage (localStorage, sessionStorage).
     */
    var domStorageEnabled: Boolean = true
}

/**
 * Create a WKWebView configured for kroom webapp embedding.
 *
 * Usage from Swift:
 * ```swift
 * let webView = KroomWebViewKt.createKroomWebView(
 *     config: KroomWebViewConfig().apply {
 *         $0.apiBaseUrl = "https://api.example.com"
 *     }
 * )
 * view.addSubview(webView)
 * webView.load(URLRequest(url: URL(string: "kroom://index")!))
 * ```
 */
@OptIn(ExperimentalForeignApi::class)
fun createKroomWebView(config: KroomWebViewConfig = KroomWebViewConfig()): WKWebView {
    ViewHandler.init()

    val schemeHandler = KroomURLSchemeHandler(config.passthroughPrefixes)

    val webViewConfig = WKWebViewConfiguration().apply {
        setURLSchemeHandler(schemeHandler, forURLScheme = "kroom")

        preferences.javaScriptEnabled = config.javaScriptEnabled

        // Enable DOM storage
        websiteDataStore = WKWebsiteDataStore.defaultDataStore()

        // Inject apiBaseUrl as window.kroomApiBase for API/SSE calls
        if (config.apiBaseUrl.isNotEmpty()) {
            val script = WKUserScript(
                source = "window.kroomApiBase = '${config.apiBaseUrl}';",
                injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                forMainFrameOnly = true
            )
            userContentController.addUserScript(script)
        }
    }

    return WKWebView(frame = cValue { }, configuration = webViewConfig)
}
