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

    private val engine = VelocityEngine()
    private var initialized = false
    private var context: Context? = null

    /**
     * Set the Android context for resource loading.
     * Must be called before init() when used in Android.
     */
    fun setContext(ctx: Context) {
        context = ctx.applicationContext
    }

    actual fun init() {
        if (initialized) return
        val velocityPropsStream = loadResource("velocity.properties")
        if (velocityPropsStream != null) {
            val velocityProperties = Properties().apply {
                load(InputStreamReader(velocityPropsStream, StandardCharsets.UTF_8))
            }
            engine.init(velocityProperties)
        } else {
            engine.init()
        }
        initialized = true
        Log.d(TAG, "ViewHandler initialized")
    }

    actual fun serve(path: String): ByteArray {
        if (!initialized) init()

        val segments = path.split('/')
        val file = segments.last()

        return if (file.contains('.')) {
            loadResource("assets/$path")?.readBytes()
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
