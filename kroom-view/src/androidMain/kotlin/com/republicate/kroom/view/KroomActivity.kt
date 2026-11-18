package com.republicate.kroom.view

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
     * Override to specify path prefixes served locally from bundled assets.
     * Everything else passes through to the server.
     * Default: common static asset prefixes
     */
    protected open fun getStaticPrefixes(): List<String> = listOf("/js/", "/css/", "/img/", "/lib/", "/fonts/", "/snd/")

    /**
     * Override to specify exact paths served locally.
     */
    protected open fun getStaticPaths(): List<String> = emptyList()

    /**
     * Override to return true for an immersive page: the status bar is hidden (a swipe
     * shows it for a moment), the navigation bar stays, and the page is drawn into the
     * display cutout (API 28+). The soft keyboard still resizes the page.
     */
    protected open fun isImmersiveMode(): Boolean = false

    /**
     * Override to configure the WebView before the URL is loaded.
     * Called after WebView settings are configured but before loadUrl.
     * Use this to add JavaScript interfaces or custom configuration.
     */
    protected open fun onWebViewReady() {}

    /**
     * Called when a network error occurs on the main document.
     * Override to customize error handling.
     * Default: shows a user-friendly error page.
     */
    protected open fun onNetworkError(errorCode: Int, description: String, url: String) {
        val html = """
            <!DOCTYPE html>
            <html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width">
            <style>
                body { font-family: system-ui, sans-serif; padding: 40px 20px; text-align: center; background: #f8f9fa; }
                h1 { color: #dc3545; font-size: 24px; }
                p { color: #6c757d; }
                button { margin-top: 20px; padding: 12px 24px; font-size: 16px; background: #007bff; color: white; border: none; border-radius: 8px; }
            </style></head>
            <body>
                <h1>Connection Error</h1>
                <p>Could not connect to server.<br>Please check that the server is running.</p>
                <button onclick="location.reload()">Retry</button>
            </body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    /**
     * Called when an HTTP error (4xx, 5xx) occurs on the main document.
     * Override to customize error handling.
     * Default: shows a user-friendly error page.
     */
    protected open fun onHttpError(statusCode: Int, reasonPhrase: String?, url: String) {
        val html = """
            <!DOCTYPE html>
            <html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width">
            <style>
                body { font-family: system-ui, sans-serif; padding: 40px 20px; text-align: center; background: #f8f9fa; }
                h1 { color: #dc3545; font-size: 24px; }
                p { color: #6c757d; }
                code { background: #e9ecef; padding: 2px 6px; border-radius: 4px; }
                button { margin-top: 20px; padding: 12px 24px; font-size: 16px; background: #007bff; color: white; border: none; border-radius: 8px; }
            </style></head>
            <body>
                <h1>Error $statusCode</h1>
                <p>${reasonPhrase ?: "Something went wrong"}</p>
                <button onclick="location.reload()">Retry</button>
            </body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ViewHandler.setContext(this)
        ViewHandler.init()

        binding = ActivityKroomBinding.inflate(layoutInflater)
        setContentView(binding.root)
        webView = binding.webview

        if (isImmersiveMode()) immerse()

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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // a dialog or the keyboard may have brought the status bar back
        if (hasFocus && isImmersiveMode()) hideStatusBar()
    }

    private fun immerse() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // The window no longer fits the page for us (nor does it at all once edge-to-edge
        // is enforced, API 35+): the root makes room for the navigation bar and the keyboard.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val state = window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()
        @Suppress("DEPRECATION") // below API 30 the keyboard is an inset only under it
        window.setSoftInputMode(state or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            // not the status bar: hidden, and shown over the page when swiped in
            val room = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime())
            root.setPadding(room.left, room.top, room.right, room.bottom)
            WindowInsetsCompat.CONSUMED
        }
        hideStatusBar()
    }

    private fun hideStatusBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
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

            // Only intercept GET requests
            if (request.method != "GET") {
                return null
            }

            // Check if path should be served locally
            val isStatic = getStaticPrefixes().any { path.startsWith(it) } ||
                    getStaticPaths().any { path == it } ||
                    path.matches(Regex("^/[^/]+\\.[a-zA-Z0-9]+$"))  // root-level static files

            if (!isStatic) {
                return null  // pass through to server
            }

            return try {
                val resourcePath = path.removePrefix("/")
                val content = ViewHandler.serve(resourcePath)
                WebResourceResponse(
                    getMimeType(resourcePath),
                    "utf-8",
                    ByteArrayInputStream(content)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to serve: $path", e)
                // Fall back to server if local serving fails
                null
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            Log.v(TAG, "${request?.method} ${request?.url}")
            return false
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError) {
            Log.e(TAG, "${error.errorCode} ${error.description} for url ${request.url}")
            // Only show error page for main document, not subresources
            if (request.isForMainFrame) {
                onNetworkError(error.errorCode, error.description.toString(), request.url.toString())
            }
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            Log.e(TAG, "${errorResponse?.statusCode} ${errorResponse?.reasonPhrase} for url ${request?.url}")
            // Only show error page for main document, not subresources
            if (request?.isForMainFrame == true && errorResponse != null) {
                onHttpError(errorResponse.statusCode, errorResponse.reasonPhrase, request.url.toString())
            }
        }
    }
}
