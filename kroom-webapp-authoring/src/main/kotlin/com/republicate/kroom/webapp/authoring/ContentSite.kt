package com.republicate.kroom.webapp.authoring

import com.republicate.kroom.webapp.assets.kroomAssets
import com.republicate.kroom.webapp.core.installCore
import com.republicate.kroom.webapp.session.installSessions
import com.republicate.kroom.webapp.velocity.installVelocity
import com.republicate.kroom.webapp.velocity.pages
import com.republicate.kroom.webapp.velocity.placeholderPages
import com.republicate.kroom.webapp.velocity.velocity
import io.ktor.server.application.*
import io.ktor.server.routing.routing
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
    config.plugins.forEach(site::register)

    installVelocity {
        templatePath = config.templatePath
        devMode = config.devDir != null
        devDir = config.devDir?.let { dir -> config.templatePath?.let { dir.resolve(it) } ?: dir }
        privateSegments = config.privateSegments
        // the `%` stack: blocks read through the very store the editor writes to
        properties["markdown.loader"] = config.storage.content
        properties["markdown.block.wrapper"] = config.wrapper
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
        placeholder = config.placeholder
        strings.putAll(config.strings)
        published = site::published
    }

    velocity.registerRequest("site") { site.view(it) }
    site.tools.forEach { (name, value) -> velocity.registerApplication(name) { value } }
    if (site.interceptors.isNotEmpty()) intercept(ApplicationCallPipeline.Plugins) {
        for (interceptor in site.interceptors) {
            interceptor(call)
            if (call.isHandled) return@intercept finish()
        }
    }
    site.startJobs()

    routing {
        kroomAssets()                 // /js/kroom/*: the house stack authoring.js builds on
        config.loginPage?.let { loginRoutes(it) }
        siteRoutes()
        site.routes.forEach { it() }
        placeholderPages(config.pagePrefix, config.pageExtension)
        pages(config.pagePrefix, config.pageExtension)
    }
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

    /** The editor's words, over its English ones — see [AuthoringConfig.strings]. */
    val strings = LinkedHashMap<String, String>()

    var lockTimeout: Duration = 2.minutes

    var sessions: Boolean = true
    var sessionSecret: String? = null

    /** What the site gains beyond its pages — see [Plugin]. Installed in this order. */
    val plugins = mutableListOf<Plugin>()

    /** Where the admin API mounts; under `/api/`, where api.js roots its calls. */
    var siteApiPrefix: String = "/api/site"

    /** Applied last over everything above — the open door. */
    val velocityProperties = LinkedHashMap<String, Any?>()
}
