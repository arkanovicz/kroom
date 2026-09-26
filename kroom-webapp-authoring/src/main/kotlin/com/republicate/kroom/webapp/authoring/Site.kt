package com.republicate.kroom.webapp.authoring

import com.republicate.kroom.PathTemplate
import com.republicate.kroom.webapp.assets.KroomAssets
import com.republicate.kroom.webapp.core.Mailer
import com.republicate.kroom.webapp.session.UserSession
import com.republicate.kroom.webapp.session.userSession
import com.republicate.kroom.webapp.velocity.pageCatalog
import com.republicate.kson.Json
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration

private val logger = LoggerFactory.getLogger("kroom.site")

/**
 * The site as its plugins see it: the services they read ([storage], [identity], [can]) and the surface they
 * register on while [Plugin.install] runs. Built by [installContentSite], which then wires every registration
 * in the order the engines need — block tools must be named before velocity is built, routes mounted after
 * the edit API — so a plugin never has to know that order.
 *
 * Layouts reach it as `$site`: `$site.head()` at the end of `<head>`, `$site.foot()` at the end of `<body>` —
 * the two calls a layout owes its plugins (and admins their bar), as `wp_head()` and `wp_footer()` are a
 * WordPress theme's.
 */
class Site internal constructor(
    val application: Application,
    val storage: Storage,
    private val roles: Roles,
    /** Where the admin API mounts; under `/api/`, where api.js roots its calls. */
    val apiPrefix: String,
    val pagePrefix: String,
    val pageExtension: String
) {
    private val registered = LinkedHashMap<String, Plugin>()
    val plugins: Collection<Plugin> get() = registered.values

    internal val tools = LinkedHashMap<String, Any>()
    internal val requestTools = LinkedHashMap<String, (ApplicationCall) -> Any>()
    internal val blockTools = LinkedHashSet<String>()
    internal val routes = ArrayList<Route.() -> Unit>()
    internal val interceptors = ArrayList<suspend (ApplicationCall) -> Unit>()
    internal val notFoundHandlers = ArrayList<suspend (ApplicationCall) -> Unit>()
    internal val publishListeners = ArrayList<suspend (Block, UserSession) -> Unit>()
    internal val jobs = ArrayList<Pair<Duration, suspend () -> Unit>>()
    private val heads = ArrayList<(ApplicationCall) -> String?>()
    private val foots = ArrayList<(ApplicationCall) -> String?>()
    private val entries = ArrayList<AdminEntry>()

    internal fun register(plugin: Plugin) {
        segment(plugin.id)
        require(plugin.id != SITE) { "'$SITE' is the site's own namespace" }
        require(plugin.id !in registered) { "plugin '${plugin.id}' registered twice" }
        registered[plugin.id] = plugin
        plugin.install(this)
        logger.info("plugin {} installed", plugin.id)
    }

    fun plugin(id: String): Plugin? = registered[id]

    // --- themes ------------------------------------------------------------------------------------

    val themes: List<Theme> get() = registered.values.filterIsInstance<Theme>()

    /** The theme a request is dressed in: an admin's `?theme=` preview, else the site's choice, else the first. */
    fun theme(call: ApplicationCall): Theme? {
        call.request.queryParameters["theme"]?.let { asked ->
            themes.firstOrNull { it.id == asked }?.takeIf { can(call.userSession, Permissions.ADMIN) }?.let { return it }
        }
        val chosen = storage.settings(SITE)["theme"]
        return themes.firstOrNull { it.id == chosen } ?: themes.firstOrNull()
    }

    /** Make [theme] the one visitors get. */
    fun activate(theme: Theme) { storage.settings(SITE)["theme"] = theme.id }

    /** The template of layout [name] in the request's theme — its `default` when it has no such layout. */
    fun layout(call: ApplicationCall, name: String?): String {
        val theme = theme(call) ?: error("#layout: no theme installed")
        val layout = name?.takeIf { it in theme.layouts } ?: "default"
        return "themes/${theme.id}/layouts/$layout.html"
    }

    internal var navigation: (ApplicationCall) -> List<NavItem> = { defaultNavigation() }

    /** Every page a visitor may land on, by its route — what a site without a menu of its own shows. */
    private fun defaultNavigation(): List<NavItem> = pages()
        .filter { it.template !in hiddenPages }
        .flatMap { it.urls }
        .filter { it != "/index" }
        .map { NavItem(it.substringAfterLast('/').replace('-', ' ').replaceFirstChar(Char::titlecase), it) }

    internal val hiddenPages = HashSet<String>()

    /** Where one logs in, when the site has its own login page. */
    var loginRoute: String? = null
        internal set

    /**
     * How the site sends mail — set by a mail plugin as it installs (or by the application), read by whoever
     * sends at send time, so install order does not matter. Null: the site sends none.
     */
    @Volatile var mailer: Mailer? = null

    // --- services ----------------------------------------------------------------------------------

    /** [plugin]'s settings, its declared defaults showing through until an admin sets them. */
    fun settings(plugin: Plugin): Settings = DefaultedSettings(storage.settings(plugin.id), plugin.settings)

    fun records(plugin: Plugin, collection: String): Records = storage.records(plugin.id, collection)

    // one answer to "who may": the edit API's, which the site shares (both are read per call, after install)
    val identity: IdentityProvider get() = application.authoring.identity

    fun roles(session: UserSession?): Set<String> = application.authoring.roles(session)

    /** Whether [session] (null: a visitor) may [permission] on [target] — "" when site-wide. */
    fun can(session: UserSession?, permission: String, target: String = ""): Boolean =
        application.authoring.can(session, permission, target)

    /**
     * Every page template, its route, and the URLs it serves: a concrete page its own; a placeholder page
     * those its blocks say exist — `pages/club/_code_.html` gives its blocks `pages/club/13Ma/…`, so a block
     * written there means `/club/13Ma` is a page. For an admin menu, a sitemap.
     */
    fun pages(): List<Page> {
        val blockDirs = storage.content.list("$pagePrefix/").map { it.substringBeforeLast('/') }.toSet()
        return application.pageCatalog(pagePrefix, pageExtension).map { (template, route) ->
            val folder = PathTemplate(template.removeSuffix(".$pageExtension").removeSuffix("/index"))
            val urls = if (folder.isConcrete) listOf(route)
                else blockDirs.mapNotNull { dir -> folder.match(dir)?.let { "/" + folder.expand(it).removePrefix("$pagePrefix/") } }.sorted()
            Page(template, route, urls)
        }
    }

    // --- registration, while plugins install ---------------------------------------------------------

    /** `$name` in every page; with [blocks], in every `%` block too — what a WordPress shortcode is. */
    fun tool(name: String, value: Any, blocks: Boolean = false) {
        tools[name] = value
        if (blocks) blockTools += name
    }

    /**
     * `$name` in every page, one value per request, made on first use by [provider] and kept for that request
     * — state a page sets and a fragment reads back later in the same render (`$seo.description("…")` before
     * `$site.head()`). With [blocks], blocks see it too.
     */
    fun requestTool(name: String, blocks: Boolean = false, provider: (ApplicationCall) -> Any) {
        requestTools[name] = provider
        if (blocks) blockTools += name
    }

    /** The value [requestTool] gave [name] for this [call], made now if it was not yet. */
    fun requestValue(name: String, call: ApplicationCall): Any? {
        val provider = requestTools[name] ?: return null
        @Suppress("UNCHECKED_CAST")
        val key = requestKeys.computeIfAbsent(name) { AttributeKey<Any>("kroom.tool.$it") } as AttributeKey<Any>
        return call.attributes.computeIfAbsent(key) { provider(call) }
    }

    private val requestKeys = java.util.concurrent.ConcurrentHashMap<String, AttributeKey<*>>()

    /** Routes of the plugin's own: public endpoints (a form's submit, a webhook) or guarded ones ([can]). */
    fun routes(block: Route.() -> Unit) { routes += block }

    /** Runs before routing, for every request; answering the call ends it there (redirects, firewall, cache). */
    fun intercept(handler: suspend (ApplicationCall) -> Unit) { interceptors += handler }

    /**
     * Runs for a request nothing answered — no route, no page — before it becomes a 404: log it, or answer it
     * (a redirect learned too late for [intercept]). The first handler to answer ends it.
     */
    fun notFound(handler: suspend (ApplicationCall) -> Unit) { notFoundHandlers += handler }

    /** A fragment for `<head>` (meta, links, scripts); null or empty adds nothing. */
    fun head(fragment: (ApplicationCall) -> String?) { heads += fragment }

    /** A fragment for the end of `<body>`. */
    fun foot(fragment: (ApplicationCall) -> String?) { foots += fragment }

    /** An entry of the admin bar, shown to whoever holds its permission. */
    fun admin(entry: AdminEntry) { entries += entry }

    /** Called after each block an author submits — off the request: a slow listener delays nobody. */
    fun onPublish(listener: suspend (Block, UserSession) -> Unit) { publishListeners += listener }

    /** Every [period], from the site's start until it stops; a failing run is logged, the next one still comes. */
    fun every(period: Duration, job: suspend () -> Unit) { jobs += period to job }

    fun grant(role: String, vararg permissions: String) = roles.grant(role, *permissions)

    /** What each role may do, as the admin bar lists it. */
    fun grants(): Map<String, Set<String>> = roles.all

    // --- what `$site` answers in a page ------------------------------------------------------------

    internal fun view(call: ApplicationCall) = SiteView(this, call)

    internal fun head(call: ApplicationCall) = heads.mapNotNull { it(call)?.takeIf(String::isNotEmpty) }.joinToString("\n")

    internal fun foot(call: ApplicationCall) = listOfNotNull(adminBar(call)).plus(
        foots.mapNotNull { it(call)?.takeIf(String::isNotEmpty) }
    ).joinToString("\n")

    /** The entries [session] may open: kroom's own, then the plugins'. */
    internal fun adminEntries(session: UserSession?): List<AdminEntry> =
        if (!can(session, Permissions.ADMIN)) emptyList()
        else (builtinEntries.filter { it.id != "themes" || themes.size > 1 } + entries).filter { can(session, it.permission) }

    private val builtinEntries = listOf(
        AdminEntry("pages", "pages"), AdminEntry("journal", "journal"), AdminEntry("media", "media"),
        AdminEntry("plugins", "plugins"), AdminEntry("themes", "themes"), AdminEntry("roles", "roles")
    )

    /**
     * The admin bar's anchor: its entries as data, the markup built by admin.js — a visitor downloads none of
     * it, as with the editor. Only for whoever holds [Permissions.ADMIN].
     */
    private fun adminBar(call: ApplicationCall): String? {
        val shown = adminEntries(call.userSession).takeIf { it.isNotEmpty() } ?: return null
        val json = Json.MutableArray().apply {
            shown.forEach { entry ->
                push(Json.MutableObject().apply {
                    set("id", entry.id); set("label", entry.label)
                    entry.icon?.let { set("icon", it) }; entry.href?.let { set("href", it) }
                    if (entry.tables.isNotEmpty()) set("tables", Json.MutableArray().apply {
                        entry.tables.forEach { table -> push(Json.MutableObject().apply { set("id", table.id); set("label", table.label); set("url", table.url) }) }
                    })
                    entry.frame?.let { set("frame", it) }
                    set("builtin", entry in builtinEntries)
                })
            }
        }.toString()
        val authoring = application.authoringOrNull
        val content = authoring?.apiPrefix ?: ""
        val v = AuthoringAssets.VERSION
        // one table of words for the editor and the bar: the application's overrides reach both
        val words = authoring?.assets?.stringTags("kroomAdmin").orEmpty()
        return """<link rel="stylesheet" href="/css/admin.css?v=$v">$words
<aside class="kroom-admin" data-api="${htmlEscape(apiPrefix)}" data-content-api="${htmlEscape(content)}" data-entries="${htmlEscape(json)}"></aside>
<script src="/js/admin.js?v=$v"></script>"""
    }

    internal suspend fun published(block: Block, author: UserSession) {
        for (listener in publishListeners) application.launch {
            try { listener(block, author) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { logger.error("publish listener failed on {}", block.path, e) }
        }
    }

    internal fun startJobs() {
        for ((period, job) in jobs) application.launch {
            while (true) {
                delay(period)
                try { job() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { logger.error("scheduled job failed", e) }
            }
        }
    }
}

data class Page(val template: String, val route: String, val urls: List<String>)

/** `$site` in a page: the slots a layout calls, the layout a page asks for, and the one question a template may ask. */
class SiteView internal constructor(private val site: Site, private val call: ApplicationCall) {
    /**
     * Everything kroom and its plugins put in `<head>`: the house scripts, the editor's for whoever is logged
     * in (a visitor downloads none of it), then the plugins' fragments.
     */
    fun head(): String = listOfNotNull(
        KroomAssets.coreScripts(),
        call.userSession?.let { site.application.authoringOrNull?.assets?.tags() },
        site.head(call).takeIf { it.isNotEmpty() }
    ).joinToString("\n")

    fun foot(): String = site.foot(call)
    fun can(permission: String, target: String = ""): Boolean = site.can(call.userSession, permission, target)

    /** What `#layout(name)` parses. */
    fun layout(name: String?): String = site.layout(call, name)

    /** Where one logs in — null when the application handles it elsewhere. */
    val login: String? get() = site.loginRoute

    /** For cache-busting a theme's assets: `?v=$site.version`. */
    val version: String get() = AuthoringAssets.VERSION
}

private class DefaultedSettings(private val stored: Settings, private val declared: List<Setting>) : Settings {
    override fun get(key: String) = stored[key] ?: declared.firstOrNull { it.key == key }?.default
    override fun set(key: String, value: String?) { stored[key] = value }
    override fun all(): Map<String, String> = declared.associate { it.key to it.default } + stored.all()
}

/** Text made safe for HTML — content and double-quoted attributes alike. */
fun htmlEscape(value: String) =
    value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

/** The site's own settings namespace (the active theme…) — no plugin may take it. */
internal const val SITE = "site"

private val SiteKey = AttributeKey<Site>("KroomSite")

val Application.site: Site get() = attributes[SiteKey]
val Application.siteOrNull: Site? get() = attributes.getOrNull(SiteKey)

internal fun Application.putSite(site: Site) = attributes.put(SiteKey, site)
