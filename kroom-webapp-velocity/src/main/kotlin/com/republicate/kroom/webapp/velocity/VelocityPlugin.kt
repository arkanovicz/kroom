package com.republicate.kroom.webapp.velocity

import com.republicate.kroom.webapp.core.WebResourceVersionCache
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.apache.velocity.VelocityContext
import org.apache.velocity.app.VelocityEngine
import org.apache.velocity.runtime.RuntimeConstants
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader
import org.apache.velocity.runtime.resource.loader.FileResourceLoader
import java.io.File
import java.io.StringWriter

/**
 * Velocity templating plugin for Ktor.
 *
 * Provides template rendering with optional translation support.
 * In dev mode with devDir set, loads templates from filesystem with hot reload.
 */
class VelocityPlugin(private val config: VelocityConfig) {

    val engine: VelocityEngine = VelocityEngine().apply {
        setProperty(RuntimeConstants.INPUT_ENCODING, "UTF-8")

        if (config.devMode && config.devDir != null) {
            // Dev mode: load from filesystem with modification checking
            setProperty(RuntimeConstants.RESOURCE_LOADER, "file")
            setProperty("file.resource.loader.class", FileResourceLoader::class.java.name)
            setProperty("file.resource.loader.path", config.devDir!!.absolutePath)
            setProperty("file.resource.loader.cache", false)
            setProperty("file.resource.loader.modificationCheckInterval", 0)
        } else {
            // Production: load from classpath
            setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath")
            setProperty("classpath.resource.loader.class", ClasspathResourceLoader::class.java.name)
            config.templatePath?.let {
                val prefix = if (it.endsWith("/")) it else "$it/"
                setProperty("classpath.resource.loader.prefix", prefix)
            }
        }

        // Load kroom macros library
        setProperty(RuntimeConstants.VM_LIBRARY, "kroom-macros.vtl")
        setProperty(RuntimeConstants.VM_LIBRARY_AUTORELOAD, config.devMode)
        init()
    }

    val versionCache: WebResourceVersionCache? = config.versionCache

    fun render(templatePath: String, model: Map<String, Any?> = emptyMap()): String {
        val template = engine.getTemplate(templatePath)
        val context = VelocityContext()
        // Add version cache as $versions
        versionCache?.let { context.put("versions", it) }
        model.forEach { (key, value) -> context.put(key, value) }
        val writer = StringWriter()
        template.merge(context, writer)
        return writer.toString()
    }

    companion object {
        private var instance: VelocityPlugin? = null

        fun getInstance(): VelocityPlugin {
            return instance ?: throw IllegalStateException("VelocityPlugin not installed")
        }
    }
}

class VelocityConfig {
    var templatePath: String? = "templates"
    var devMode: Boolean = false
    var devDir: File? = null  // Source directory for dev mode hot reload
    var versionCache: WebResourceVersionCache? = null
}

/**
 * Install Velocity templating plugin.
 */
fun Application.installVelocity(block: VelocityConfig.() -> Unit = {}) {
    val config = VelocityConfig().apply(block)
    val plugin = VelocityPlugin(config)

    // Store in application attributes for access from routes
    attributes.put(VelocityPluginKey, plugin)
}

private val VelocityPluginKey = io.ktor.util.AttributeKey<VelocityPlugin>("VelocityPlugin")

/**
 * Get Velocity plugin from application.
 */
val Application.velocity: VelocityPlugin
    get() = attributes[VelocityPluginKey]

/**
 * Get Velocity plugin from call.
 */
val ApplicationCall.velocity: VelocityPlugin
    get() = application.velocity

/**
 * Respond with rendered Velocity template.
 */
suspend fun ApplicationCall.respondVelocity(
    templatePath: String,
    model: Map<String, Any?> = emptyMap(),
    contentType: ContentType = ContentType.Text.Html
) {
    val html = velocity.render(templatePath, model)
    respondText(html, contentType)
}

/**
 * Respond with rendered Velocity template using DSL.
 */
suspend fun ApplicationCall.respondVelocity(
    templatePath: String,
    contentType: ContentType = ContentType.Text.Html,
    block: MutableMap<String, Any?>.() -> Unit
) {
    val model = mutableMapOf<String, Any?>().apply(block)
    respondVelocity(templatePath, model, contentType)
}

/**
 * Extension for RoutingContext.
 */
suspend fun RoutingContext.respondVelocity(
    templatePath: String,
    model: Map<String, Any?> = emptyMap(),
    contentType: ContentType = ContentType.Text.Html
) {
    call.respondVelocity(templatePath, model, contentType)
}

suspend fun RoutingContext.respondVelocity(
    templatePath: String,
    contentType: ContentType = ContentType.Text.Html,
    block: MutableMap<String, Any?>.() -> Unit
) {
    call.respondVelocity(templatePath, contentType, block)
}
