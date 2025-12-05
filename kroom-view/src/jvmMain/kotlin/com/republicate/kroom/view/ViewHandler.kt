package com.republicate.kroom.view

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.velocity.VelocityContext
import org.apache.velocity.app.VelocityEngine
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Properties

private val logger = KotlinLogging.logger("kroom-view")

actual object ViewHandler {

    private val engine = VelocityEngine()
    private var initialized = false

    actual fun init() {
        if (initialized) return
        val velocityPropsStream = loadResource("velocity.properties")
        if (velocityPropsStream != null) {
            val velocityProperties = Properties().apply {
                load(InputStreamReader(velocityPropsStream, StandardCharsets.UTF_8))
            }
            engine.init(velocityProperties)
        } else {
            // Default configuration
            engine.init()
        }
        initialized = true
        logger.debug { "ViewHandler initialized" }
    }

    actual fun serve(path: String): ByteArray {
        if (!initialized) init()

        val segments = path.split('/')
        val file = segments.last()

        return if (file.contains('.')) {
            // Static asset
            loadResource("assets/$path")?.readBytes()
                ?: throw IllegalArgumentException("Resource not found: $path")
        } else {
            // Template
            val buffer = ByteArrayOutputStream()
            val context = VelocityContext(mapOf("page" to "templates/$path.html"))
            val layout = engine.getTemplate("layouts/base.html")
            OutputStreamWriter(buffer, StandardCharsets.UTF_8).use {
                layout.merge(context, it)
            }
            buffer.toByteArray()
        }
    }

    private fun loadResource(path: String): InputStream? =
        (Thread.currentThread().contextClassLoader ?: ViewHandler::class.java.classLoader)
            .getResourceAsStream(path.removePrefix("/"))
}
