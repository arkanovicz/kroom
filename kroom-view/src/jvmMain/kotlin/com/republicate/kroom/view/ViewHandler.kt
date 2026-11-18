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
            logger.debug { "Velocity started" }
        }
    }

    /** Starts Velocity now rather than with the first template. */
    actual fun init() {
        engine
    }

    actual fun serve(path: String): ByteArray {
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
