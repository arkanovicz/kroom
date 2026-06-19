package com.republicate.kroom.webapp.velocity

import com.republicate.kroom.webapp.core.WebResourceVersionCache
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.apache.velocity.VelocityContext
import org.apache.velocity.app.VelocityEngine
import org.apache.velocity.context.AbstractContext
import org.apache.velocity.context.Context
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
class VelocityPlugin(config: VelocityConfig) {

    val engine: VelocityEngine = VelocityEngine().apply {
        setProperty(RuntimeConstants.INPUT_ENCODING, "UTF-8")

        if (config.devMode && config.devDir != null) {
            // Dev mode: file first (hot reload), then classpath (for macros library)
            setProperty(RuntimeConstants.RESOURCE_LOADER, "file,classpath")
            setProperty("file.resource.loader.class", FileResourceLoader::class.java.name)
            setProperty("file.resource.loader.path", config.devDir!!.absolutePath)
            setProperty("file.resource.loader.cache", false)
            setProperty("file.resource.loader.modificationCheckInterval", 0)
            setProperty("classpath.resource.loader.class", ClasspathResourceLoader::class.java.name)
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

        // Auto-register TranslateDirective if l10n module is on classpath
        val translateDirective = "com.republicate.kroom.webapp.l10n.TranslateDirective"
        try {
            Class.forName(translateDirective)
            setProperty("runtime.custom_directives", translateDirective)
        } catch (_: ClassNotFoundException) {
            // l10n module not present, skip
        }

        init()
    }

    val versionCache: WebResourceVersionCache? = config.versionCache

    // Live scope registries. Populated at install (config block) and post-install by other
    // plugins (e.g. l10n registers $lang). Read-only at render time; mutated only during startup.
    private val applicationProviders = LinkedHashMap(config.applicationProviders)
    private val sessionProviders = LinkedHashMap(config.sessionProviders)
    private val requestProviders = LinkedHashMap(config.requestProviders)

    init {
        // kroom core owns $versions (application scope)
        versionCache?.let { vc -> applicationProviders.putIfAbsent("versions") { vc } }
    }

    /** Register an application-scope value (singleton, call-independent). */
    fun registerApplication(key: String, provider: () -> Any?) { applicationProviders[key] = provider }
    /** Register a session-scope value (resolved per call, e.g. `$user`). */
    fun registerSession(key: String, provider: (ApplicationCall) -> Any?) { sessionProviders[key] = provider }
    /** Register a request-scope value (resolved per call, e.g. `$lang`). */
    fun registerRequest(key: String, provider: (ApplicationCall) -> Any?) { requestProviders[key] = provider }

    /**
     * Low-level, call-less render: flat context with `$versions` + model. Unchanged — for callers
     * that have no [ApplicationCall] (no session/request scope available).
     */
    fun render(templatePath: String, model: Map<String, Any?> = emptyMap()): String {
        val context = VelocityContext()
        versionCache?.let { context.put("versions", it) }
        model.forEach { (key, value) -> context.put(key, value) }
        return renderContext(templatePath, context)
    }

    /** Merge a prepared context. Shared by every render path. */
    fun renderContext(templatePath: String, context: Context): String {
        val template = engine.getTemplate(templatePath)
        val writer = StringWriter()
        template.merge(context, writer)
        return writer.toString()
    }

    /**
     * Build the read-only scope chain for a call: application ⊂ session ⊂ request, with the route
     * [model] most specific. A fresh top context absorbs `#set` so the scopes stay read-only.
     * Lookups fall through request → session → application; most specific wins.
     */
    fun scopedContext(call: ApplicationCall, model: Map<String, Any?>): Context {
        val app = ScopeContext(applicationProviders.mapValues { (_, p) -> { _: ApplicationCall -> p() } }, call, null)
        val session = ScopeContext(sessionProviders, call, app)
        val request = ScopeContext(requestProviders, call, session)
        val withModel = if (model.isEmpty()) request else VelocityContext(model, request)
        return VelocityContext(withModel) // scratch top for #set
    }

    /** Call-aware render: resolves the scope chain and renders through [renderContext]. */
    fun renderForCall(call: ApplicationCall, templatePath: String, model: Map<String, Any?> = emptyMap()): String =
        renderContext(templatePath, scopedContext(call, model))

    companion object {
        private var instance: VelocityPlugin? = null

        fun getInstance(): VelocityPlugin {
            return instance ?: throw IllegalStateException("VelocityPlugin not installed")
        }
    }
}

/**
 * A read-only Velocity scope: resolves keys lazily through registered providers, memoizing per
 * render, and falls through to the chained (less-specific) scope. `#set` never lands here — a
 * fresh top context absorbs writes — so the scope stays a pure read-through view.
 */
internal class ScopeContext(
    private val providers: Map<String, (ApplicationCall) -> Any?>,
    private val call: ApplicationCall,
    inner: Context?
) : AbstractContext(inner) {
    private val cache = HashMap<String, Any?>()

    override fun internalGet(key: String): Any? {
        if (cache.containsKey(key)) return cache[key]
        val provider = providers[key] ?: return null // not ours → fall through to chained scope
        return provider(call).also { cache[key] = it }
    }

    override fun internalContainsKey(key: String): Boolean = providers.containsKey(key)
    override fun internalGetKeys(): Array<String> = providers.keys.toTypedArray()
    override fun internalPut(key: String, value: Any?): Any? = null // read-only
    override fun internalRemove(key: String): Any? = null           // read-only
}

class VelocityConfig {
    var templatePath: String? = "templates"
    var devMode: Boolean = false
    var devDir: File? = null  // Source directory for dev mode hot reload
    var versionCache: WebResourceVersionCache? = null

    internal val applicationProviders = LinkedHashMap<String, () -> Any?>()
    internal val sessionProviders = LinkedHashMap<String, (ApplicationCall) -> Any?>()
    internal val requestProviders = LinkedHashMap<String, (ApplicationCall) -> Any?>()

    /** Register an application-scope value (singleton, call-independent), e.g. `application("brand") { "…" }`. */
    fun application(key: String, provider: () -> Any?) { applicationProviders[key] = provider }
    /** Register a session-scope value, e.g. `session("user") { it.userSession }`. */
    fun session(key: String, provider: (ApplicationCall) -> Any?) { sessionProviders[key] = provider }
    /** Register a request-scope value, e.g. `request("bubble") { … }`. */
    fun request(key: String, provider: (ApplicationCall) -> Any?) { requestProviders[key] = provider }
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
 * Velocity plugin if installed, else null — for optional cross-module wiring (e.g. l10n
 * registering its scope keys only when velocity is present).
 */
val Application.velocityOrNull: VelocityPlugin?
    get() = attributes.getOrNull(VelocityPluginKey)

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
    val html = velocity.renderForCall(this, templatePath, model)
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
