package com.republicate.kroom.webapp.authoring

import com.republicate.kroom.webapp.assets.kroomAssets
import com.republicate.kroom.webapp.core.installCore
import com.republicate.kroom.webapp.session.installSessions
import com.republicate.kroom.webapp.velocity.installVelocity
import com.republicate.kroom.webapp.velocity.pages
import com.republicate.kroom.webapp.velocity.placeholderPages
import com.republicate.kroom.webapp.velocity.velocity
import io.ktor.server.application.*
import io.ktor.util.pipeline.PipelinePhase
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A content site in one call: `#` layouts from the application's own resources, `%` markdown blocks from
 * [ContentSiteConfig.storage]'s content, the edit API over them, and the pages routed — including the ones whose path
 * carries a placeholder (`pages/club/_code_.html` → `/club/{code}`).
 *
 * It is a starting point, not a frame: every piece it installs is a plain `installX` an application can
 * call itself, and [ContentSiteConfig.velocityProperties] is applied last, so any decision here can be
 * overridden rather than worked around.
 */
fun Application.installContentSite(block: ContentSiteConfig.() -> Unit = {}) {
    val config = ContentSiteConfig().apply(block)

    // one tree in dev, laid out as the classpath production reads: `<devDir>/templates`, `<devDir>/static`
    installCore { static { devMode = config.devDir != null; devDir = config.devDir?.resolve("static") } }
    if (config.sessions) installSessions { config.sessionSecret?.let { sessionSecret = it } }

    // plugins only register: what they bring is wired below, each piece where the engines need it
    val site = Site(this, config.storage, config.roles, config.siteApiPrefix, config.pagePrefix, config.pageExtension)
    putSite(site)
    site.words.putAll(config.strings)
    site.declaredSettings += config.settings
    // the site's own before the plugins': its card heads the head, its redirects answer first, and what no
    // template answers may be an editor's page
    site.installCard()
    site.installRedirects(config.redirectResolvers)
    site.notFound { call -> site.authored.serve(call) }
    config.plugins.forEach(site::register)
    // a page's #layout never lands on nothing: without a theme of its own, a site wears kroom's
    if (site.themes.isEmpty()) site.register(BasicTheme())

    installVelocity {
        templatePath = config.templatePath
        devMode = config.devDir != null
        devDir = config.devDir?.let { dir -> config.templatePath?.let { dir.resolve(it) } ?: dir }
        privateSegments = config.privateSegments
        // the `%` stack: blocks read through the very store the editor writes to
        properties["markdown.loader"] = config.storage.content
        properties["markdown.block.wrapper"] = config.wrapper
        // #layout(name): a page's parts, handed down to its theme's layout
        properties["velocimacro.library.path"] = "kroom-macros.vtl,kroom/layout.vtl"
        config.placeholder?.let { properties["markdown.missing"] = it }
        val blockTools = config.blockTools + site.blockTools
        if (blockTools.isNotEmpty()) properties["markdown.tools"] = blockTools.joinToString(",")
        properties.putAll(config.velocityProperties)
    }

    installAuthoring {
        store = config.storage.content
        lockTimeout = config.lockTimeout
        identity = config.identity
        roles = config.roles
        media = config.storage.media
        placeholder = config.placeholder
        language = config.language
        published = site::published
    }

    velocity.registerRequest("site") { site.view(it) }
    velocity.registerRequest("theme") { call -> site.theme(call)?.let { ThemeView(it, site.settings(it)) } }
    velocity.registerRequest("nav") { call -> site.navigation(call) }
    site.navigation = config.navigation
    site.requestLanguage = config.requestLanguage
    config.loginPage?.let { page ->
        site.hiddenPages += page
        site.loginRoute = "/" + page.removePrefix("${config.pagePrefix}/").removeSuffix(".${config.pageExtension}")
    }
    // a tool of a plugin that is off resolves to nothing: a block calling it fails, and renders `broken`
    site.tools.forEach { (name, owned) -> velocity.registerApplication(name) { owned.value.takeIf { site.enabled(owned.owner) } } }
    site.requestTools.keys.forEach { name -> velocity.registerRequest(name) { call -> site.requestValue(name, call) } }
    if (site.interceptors.isNotEmpty()) intercept(ApplicationCallPipeline.Plugins) {
        for (interceptor in site.interceptors) {
            interceptor(call)
            if (call.isHandled) return@intercept finish()
        }
    }
    // a phase of our own, ahead of Fallback, where the engine answers 404 before any later interceptor looks
    if (site.notFoundHandlers.isNotEmpty()) {
        val notFound = PipelinePhase("KroomNotFound")
        insertPhaseBefore(ApplicationCallPipeline.Fallback, notFound)
        intercept(notFound) {
            for (handler in site.notFoundHandlers) {
                if (call.isHandled) return@intercept
                handler(call)
            }
        }
    }
    site.startJobs()

    routing {
        kroomAssets()                 // /js/kroom/*: the house stack authoring.js builds on
        config.loginPage?.let { loginRoutes(it) }
        siteRoutes()
        // a plugin's routes mount under a pass-through child whose interceptor answers 404 while the plugin is off
        site.routes.forEach { owned ->
            val owner = owned.owner ?: return@forEach owned.value(this)
            createChild(Transparent).apply {
                intercept(ApplicationCallPipeline.Plugins) {
                    if (!site.enabled(owner)) { call.respond(HttpStatusCode.NotFound); finish() }
                }
                owned.value(this)
            }
        }
        placeholderPages(config.pagePrefix, config.pageExtension)
        pages(config.pagePrefix, config.pageExtension)
    }
}

/** A route selector matching nothing of the path: a place to hang an interceptor over a plugin's routes. */
private object Transparent : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) = RouteSelectorEvaluation.Transparent
}

class ContentSiteConfig {
    /** Everything the site keeps: its blocks, read and written, and its plugins' settings and records. */
    var storage: Storage = MemoryStorage()

    /** Who is who, and their roles — see [AuthoringConfig.identity]. */
    var identity: IdentityProvider = MemoryIdentityProvider()

    /** What each role may do. */
    var roles: Roles = Roles()

    /**
     * The page a login form is (`pages/login.html`): when set, `POST /login` checks `user`/`password` against
     * [identity] and goes back to `from`, re-rendering this page with `$failed` when refused; `POST /logout`
     * ends the session. Null leaves logging in to the application (kroom-webapp-auth, oauth).
     */
    var loginPage: String? = null

    /** Classpath root of the `#` layouts; null keeps them at the classpath root. */
    var templatePath: String? = "templates"

    /**
     * A source resources directory (`src/main/resources`) to serve from instead of the classpath, hot-reloaded:
     * the layouts under its [templatePath], the static files under its `static/` — dev only.
     */
    var devDir: File? = null

    var pagePrefix: String = "pages"

    /** Directory names under [pagePrefix] holding partials, never served as pages. */
    var privateSegments: Set<String> = setOf("inc")
    var pageExtension: String = "html"

    /** The template decorating each block; kroom's own ships in this module. */
    var wrapper: String = "kroom/block-wrapper.html"

    /**
     * Page-context tools every block may use, by name. A block sees nothing else of the page but what the
     * page passes it: `#markdown("description", {"club": $club})`.
     */
    val blockTools = mutableListOf<String>()

    /** What a page shows where a block has not been written yet (`%` markdown, `$name` in scope). */
    var placeholder: String? = null

    /** The language the editor and the admin bar speak — see [AuthoringConfig.language]. */
    var language: String = "auto"

    /**
     * The plugins' words, by the ids the admin bar keys them on (`<plugin>.name`, `<plugin>.<setting>`,
     * `<entry id>`, …): what a plugin says of itself is the application's to translate. kroom's own words are
     * shipped, in [language]'s stock.
     */
    val strings = LinkedHashMap<String, String>()

    var lockTimeout: Duration = 2.minutes

    var sessions: Boolean = true
    var sessionSecret: String? = null

    /** The application's own site settings, after kroom's, in the *site* admin entry — see [Site.declaredSettings]. */
    val settings = mutableListOf<Setting>()

    /** `@name` targets of the site's redirect rules: what only the application knows, given the rule's captures. */
    val redirectResolvers = LinkedHashMap<String, (Map<String, String>) -> String?>()

    /** What the site gains beyond its pages — see [Plugin]. Installed in this order. */
    val plugins = mutableListOf<Plugin>()

    /** The menu `$nav` offers a theme, per request; null: the one an admin stored, else derived from the pages. */
    var navigation: ((ApplicationCall) -> List<NavItem>)? = null

    /** How a request's language is known — `{ it.language }` with kroom-webapp-l10n installed; null: the site's default. */
    var requestLanguage: ((ApplicationCall) -> String?)? = null

    /** Where the admin API mounts; under `/api/`, where api.js roots its calls. */
    var siteApiPrefix: String = "/api/site"

    /** Applied last over everything above — the open door. */
    val velocityProperties = LinkedHashMap<String, Any?>()
}
