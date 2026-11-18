package com.republicate.kroom.view

import android.content.Context
import android.util.Log
import org.apache.velocity.VelocityContext
import org.apache.velocity.app.VelocityEngine
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Properties

private const val TAG = "kroom-view"

actual object ViewHandler {

    // started by the first template rendered: a static file needs no Velocity
    private val engine by lazy {
        VelocityEngine().apply {
            val properties = loadResource("velocity.properties")
            if (properties != null) {
                init(Properties().apply {
                    properties.use { load(InputStreamReader(it, StandardCharsets.UTF_8)) }
                })
            } else {
                init()
            }
            Log.d(TAG, "Velocity started")
        }
    }
    private var context: Context? = null

    /**
     * Set the Android context for resource loading.
     * Must be called before anything is served when used in Android.
     */
    fun setContext(ctx: Context) {
        context = ctx.applicationContext
    }

    /** Starts Velocity now rather than with the first template. */
    actual fun init() {
        engine
    }

    actual fun serve(path: String): ByteArray {
        val segments = path.split('/')
        val file = segments.last()

        return if (file.contains('.')) {
            // Try Android assets folder first, then static/ (jar dependencies)
            (loadResource(path) ?: loadResource("static/$path"))?.readBytes()
                ?: throw IllegalArgumentException("Resource not found: $path")
        } else {
            val buffer = ByteArrayOutputStream()
            val velocityContext = VelocityContext(mapOf("page" to "templates/$path.html"))
            val layout = engine.getTemplate("layouts/base.html")
            OutputStreamWriter(buffer, StandardCharsets.UTF_8).use {
                layout.merge(velocityContext, it)
            }
            buffer.toByteArray()
        }
    }

    private fun loadResource(path: String): InputStream? {
        val cleanPath = path.removePrefix("/")
        // Try Android assets first if context available
        context?.let { ctx ->
            try {
                return ctx.assets.open(cleanPath)
            } catch (_: Exception) {
                // Fall through to classloader
            }
        }
        // Fallback to classloader
        return (Thread.currentThread().contextClassLoader ?: ViewHandler::class.java.classLoader)
            .getResourceAsStream(cleanPath)
    }
}
