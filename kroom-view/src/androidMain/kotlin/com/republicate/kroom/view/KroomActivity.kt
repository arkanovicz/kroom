package com.republicate.kroom.view

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.MimeTypeMap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.republicate.kroom.view.databinding.ActivityKroomBinding
import java.io.ByteArrayInputStream

private const val TAG = "kroom"

/**
 * Base activity for embedding a kroom webapp in a fullscreen WebView.
 *
 * Subclass this and implement [getSiteUrl] to specify your webapp's URL.
 * The activity intercepts HTTP requests and serves local assets via [ViewHandler],
 * while letting /api and /events (SSE) pass through to the actual server.
 */
abstract class KroomActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKroomBinding
    protected lateinit var webView: WebView

    /**
     * Return the base URL of your kroom webapp server.
     * Example: "https://myapp.example.com" or "http://10.0.2.2:8080" for local dev
     */
    protected abstract fun getSiteUrl(): String

    /**
     * Override to specify paths that should NOT be intercepted (pass through to server).
     * Default: /api and /events (SSE)
     */
    protected open fun getPassthroughPrefixes(): List<String> = listOf("/api", "/events")

    /**
     * Override to configure the WebView before the URL is loaded.
     * Called after WebView settings are configured but before loadUrl.
     * Use this to add JavaScript interfaces or custom configuration.
     */
    protected open fun onWebViewReady() {}

    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ViewHandler.setContext(this)
        ViewHandler.init()

        binding = ActivityKroomBinding.inflate(layoutInflater)
        setContentView(binding.root)
        webView = binding.webview

        webView.webViewClient = KroomWebViewClient()

        with(webView.settings) {
            domStorageEnabled = true
            javaScriptEnabled = true
            builtInZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        onWebViewReady()

        Log.d(TAG, "Loading ${getSiteUrl()}")
        webView.loadUrl(getSiteUrl())
        webView.requestFocus(View.FOCUS_DOWN or View.FOCUS_UP)
    }

    private val mimeTypeMap = MimeTypeMap.getSingleton()

    private fun getMimeType(uri: String): String {
        val ext = uri.substringBefore('?').substringAfterLast('.', "")
        return if (ext.isEmpty()) "text/html"
        else mimeTypeMap.getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private inner class KroomWebViewClient : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val path = request.url.path ?: return null

            // Pass through to server for API and SSE
            for (prefix in getPassthroughPrefixes()) {
                if (path.startsWith(prefix)) {
                    return null
                }
            }

            // Only intercept GET requests
            if (request.method != "GET") {
                return null
            }

            return try {
                val resourcePath = if (path == "/" || path.isEmpty()) "index" else path.removePrefix("/")
                val content = ViewHandler.serve(resourcePath)
                WebResourceResponse(
                    getMimeType(resourcePath),
                    "utf-8",
                    ByteArrayInputStream(content)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to serve: $path", e)
                WebResourceResponse(
                    "text/plain",
                    "utf-8",
                    404,
                    "Not Found",
                    emptyMap(),
                    ByteArrayInputStream("Resource not found: $path".toByteArray())
                )
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            Log.v(TAG, "${request?.method} ${request?.url}")
            return false
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError) {
            Log.e(TAG, "${error.errorCode} ${error.description} for url ${request.url}")
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            Log.e(TAG, "${errorResponse?.reasonPhrase} for url ${request?.url}")
        }
    }
}
