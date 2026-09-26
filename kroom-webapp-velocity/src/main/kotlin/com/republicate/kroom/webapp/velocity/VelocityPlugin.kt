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
import org.apache.velocity.util.ClassUtils
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
            setProperty(RuntimeConstants.RESOURCE_LOADERS, "file,classpath")
            setProperty("resource.loader.file.class", FileResourceLoader::class.java.name)
            setProperty("resource.loader.file.path", config.devDir!!.absolutePath)
            setProperty("resource.loader.file.cache", false)
            setProperty("resource.loader.file.modification_check_interval", 0)
            setProperty("resource.loader.classpath.class", ClasspathResourceLoader::class.java.name)
        } else if (config.templatePath != null) {
            // Production: templates under templatePath by bare name, macros library at classpath root
            setProperty(RuntimeConstants.RESOURCE_LOADERS, "templates,root")
            setProperty("resource.loader.templates.class", ClasspathResourceLoader::class.java.name)
            setProperty("resource.loader.templates.path", config.templatePath)
            setProperty("resource.loader.root.class", ClasspathResourceLoader::class.java.name)
        } else {
            // Production: everything at classpath root
            setProperty(RuntimeConstants.RESOURCE_LOADERS, "classpath")
            setProperty("resource.loader.classpath.class", ClasspathResourceLoader::class.java.name)
        }

        // Load kroom macros library
        setProperty(RuntimeConstants.VM_LIBRARY, "kroom-macros.vtl")
        setProperty(RuntimeConstants.VM_LIBRARY_AUTORELOAD, config.devMode)

        config.properties.forEach { (key, value) -> setProperty(key, value) }

        // Auto-register the directives whose module happens to be on the classpath. Resolve each the
        // way the engine will (thread-context loader, then its own): a name the engine can't find is a
        // fatal init error, so asking a different loader than it does would turn "absent" into a crash.
        val present = OPTIONAL_DIRECTIVES.filter {
            try { ClassUtils.getClass(it); true } catch (_: ClassNotFoundException) { false }
        }
        if (present.isNotEmpty()) {
            val declared = config.properties["runtime.custom_directives"]?.toString()?.takeIf { it.isNotBlank() }
            setProperty("runtime.custom_directives", (listOfNotNull(declared) + present).joinToString(","))
        }

        init()

        // kroom-markdown, when deployed: a velocity 3.0 native macro, configured by the `markdown.*`
        // properties (prefix stripped). Same classpath question as the directives above, asked the same way.
        try {
            val factory = ClassUtils.getClass(MARKDOWN_MACRO).getMethod("fromProperties", Map::class.java)
            val markdown = config.properties.filterKeys { it.startsWith("markdown.") }.mapKeys { it.key.removePrefix("markdown.") }
            addMacro("markdown", factory.invoke(null, markdown) as org.apache.velocity.engine.VtlMacro)
        } catch (_: ClassNotFoundException) { /* kroom-markdown not deployed */ }
    }

    val versionCache: WebResourceVersionCache? = config.versionCache

    // where templates are read from — what [placeholderPages] has to browse to find the parameterized ones
    val templatePath: String? = config.templatePath
    val devDir: File? = config.devDir?.takeIf { config.devMode }

    val privateSegments: Set<String> = config.privateSegments

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
     * How [pages] renders a resolved template. Defaults to [respondVelocity]; settable declaratively
     * via `installVelocity { pageRenderer = … }`, and l10n overrides it to
     * [com.republicate.kroom.webapp.l10n.respondVelocityTranslated] on install, so content pages
     * translate automatically. Read per request, so install order is irrelevant.
     */
    var pageRenderer: suspend ApplicationCall.(String) -> Unit = config.pageRenderer ?: { respondVelocity(it) }

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
        /** Sibling modules' custom directives, by name: each registers iff its module is deployed.
         *  No dependency either way — the classpath is the only thing asked. */
        private const val MARKDOWN_MACRO = "com.republicate.kroom.markdown.MarkdownMacro"

        private val OPTIONAL_DIRECTIVES = listOf(
            "com.republicate.kroom.webapp.l10n.TranslateDirective"
        )

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

    /** Extra engine properties, applied over kroom's defaults (e.g. `markdown.*` for `#markdown` blocks).
     *  `runtime.custom_directives` is appended to, not replaced: the auto-registered directives stay. */
    val properties = LinkedHashMap<String, Any?>()

    internal val applicationProviders = LinkedHashMap<String, () -> Any?>()
    internal val sessionProviders = LinkedHashMap<String, (ApplicationCall) -> Any?>()
    internal val requestProviders = LinkedHashMap<String, (ApplicationCall) -> Any?>()

    /**
     * Directory names never served as pages, wherever they sit — where partials live beside the pages that
     * `#parse` them (`pages/inc/header.html`). A dotted name (`header.inc.html`) is never served either way.
     */
    var privateSegments: Set<String> = setOf("inc")

    /** How `Route.pages()` renders a resolved template; null keeps the default `respondVelocity`. */
    var pageRenderer: (suspend ApplicationCall.(String) -> Unit)? = null

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

// A path segment safe to splice into a resource lookup: no dots (kills `..`, dotfiles, and any
// `foo.txt`/`header.inc` suffix), no slashes (kills `%2f`-smuggled separators), no empties.
private val SAFE_SEGMENT = Regex("[A-Za-z0-9_-]+")

/** Whether a page path made of [segments] may be served: every segment clean, none private (`inc/`). */
internal fun VelocityPlugin.routable(segments: List<String>): Boolean =
    segments.isNotEmpty() && segments.all { SAFE_SEGMENT.matches(it) && it !in privateSegments }

/**
 * Resolve [path] (one or more `/`-separated clean segments, e.g. `source` or `legal/terms`) to a
 * template `"$prefix/$path.$extension"` and, if it exists, render it via [VelocityPlugin.pageRenderer]
 * — so the per-request base context (`$user` …) applies and, when l10n is installed, the page is
 * translated. Returns `true` if a page was served, `false` if the path is unsafe or no template
 * backs it, leaving the response untouched so the caller can fall through (404, next route, …).
 *
 * This is the reusable primitive behind [pages]: a param-route handler (e.g. `/{community}`, which
 * ktor scores above a tailcard) can delegate its no-match branch here instead of reimplementing the
 * lookup. Unsafe paths (traversal, dotfiles, partials like `header.inc`) never resolve. Existence is
 * checked separately from rendering, so a `ResourceNotFoundException` from a nested `#parse` in a
 * real page still surfaces as 500 — a template bug isn't masked as a missing page.
 */
suspend fun ApplicationCall.servePage(
    path: String,
    prefix: String = "pages",
    extension: String = "html"
): Boolean {
    val segments = path.split('/').filter { it.isNotEmpty() }
    if (!velocity.routable(segments)) return false
    val template = "${prefix.trimEnd('/')}/${segments.joinToString("/")}.${extension.trimStart('.')}"
    if (!velocity.engine.resourceExists(template)) return false
    velocity.pageRenderer(this, template)
    return true
}

/**
 * Mount a convention that renders a clean URI as a template: `/source` → `pages/source.html`,
 * `/legal/terms` → `pages/legal/terms.html`. A content page becomes *just a template* — no route,
 * no model.
 *
 * Mount it **last**, after all specific and param routes: it serves any otherwise-unmatched GET a template
 * backs, and leaves the others unanswered — a 404, once the application's fallbacks have had their look. Note ktor scores a param route (`/{x}`) **above** this
 * tailcard, so an app with a root param route should instead delegate from that handler via
 * [servePage]. See [servePage] for the lookup/security details.
 */
fun Route.pages(prefix: String = "pages", extension: String = "html") {
    get("/{path...}") {
        val path = call.parameters.getAll("path").orEmpty().joinToString("/")
        // a miss is left unanswered, not answered 404: whatever handles unhandled calls (a redirect, a log)
        // still gets to see it, and the engine says 404 if nothing does
        call.servePage(path, prefix, extension)
    }
}
