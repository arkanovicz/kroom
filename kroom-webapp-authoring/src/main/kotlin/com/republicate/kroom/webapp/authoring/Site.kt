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

    /** The plugins' words in the admin bar, as the application translates them — `ContentSiteConfig.strings`. */
    internal val words = LinkedHashMap<String, String>()

    /**
     * The site's own configuration, declared like a plugin's: kroom's settings first (what the site says of
     * itself, how it mails, what it redirects), then the application's (`ContentSiteConfig.settings`). Stored
     * under the `site` namespace, shown by the admin bar's *site* entry, read in templates as `$site.name`,
     * `$site.lang`, … and `$site.settings.<key>`.
     */
    val declaredSettings = ArrayList<Setting>(SITE_SETTINGS)

    /** The site's settings, its declared defaults showing through until an admin sets them. */
    fun settings(): Settings = DefaultedSettings(storage.settings(SITE), declaredSettings)

    /** The site's public URL, no trailing slash; empty when unset. */
    val baseUrl: String get() = settings()["baseUrl"]?.trim()?.trimEnd('/').orEmpty()

    // what the plugins recorded, each piece tagged with its owner, so a disabled plugin falls silent everywhere
    internal val tools = LinkedHashMap<String, Owned<Any>>()
    internal val requestTools = LinkedHashMap<String, Owned<(ApplicationCall) -> Any>>()
    internal val blockTools = LinkedHashSet<String>()
    internal val routes = ArrayList<Owned<Route.() -> Unit>>()
    internal val interceptors = ArrayList<suspend (ApplicationCall) -> Unit>()
    internal val notFoundHandlers = ArrayList<suspend (ApplicationCall) -> Unit>()
    internal val publishListeners = ArrayList<suspend (Block, UserSession) -> Unit>()
    internal val jobs = ArrayList<Pair<Duration, suspend () -> Unit>>()
    private val heads = ArrayList<(ApplicationCall) -> String?>()
    private val foots = ArrayList<(ApplicationCall) -> String?>()
    private val entries = ArrayList<Owned<AdminEntry>>()

    /** The plugin whose [Plugin.install] is running — what it records is its; null for the site's own. */
    private var installing: Plugin? = null

    internal fun register(plugin: Plugin) {
        segment(plugin.id)
        require(plugin.id != SITE) { "'$SITE' is the site's own namespace" }
        require(plugin.id !in registered) { "plugin '${plugin.id}' registered twice" }
        registered[plugin.id] = plugin
        installing = plugin
        try { plugin.install(this) } finally { installing = null }
        logger.info("plugin {} installed{}", plugin.id, if (enabled(plugin)) "" else ", disabled")
    }

    // --- enabled or not: a plugin installed stays installed, an admin switches it live --------------------

    @Volatile private var disabled: Set<String> = storage.settings(SITE)["plugins.disabled"].orEmpty()
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Whether [plugin]'s registrations answer — the site's own (null) always do, a theme always does. */
    fun enabled(plugin: Plugin?): Boolean = plugin == null || plugin is Theme || plugin.id !in disabled

    /**
     * Switch [plugin] [on] or off, live. Enabling asks the plugin's [Plugin.check] first: what it answers is
     * answered here, and the plugin stays off. Null: done.
     */
    fun enable(plugin: Plugin, on: Boolean): String? {
        if (plugin is Theme) return null
        if (on) plugin.check(settings(plugin))?.let { return it }
        disabled = if (on) disabled - plugin.id else disabled + plugin.id
        storage.settings(SITE)["plugins.disabled"] = disabled.sorted().joinToString(",").ifEmpty { null }
        return null
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
     * How the site sends mail: the application's own transport when it set one, else SMTP over the site's
     * *Mail* settings once a host is set — read by whoever sends at send time, so install order does not
     * matter. Null: the site sends none.
     */
    @Volatile var mailer: Mailer? = null
        get() = field ?: smtp.takeIf { !settings()["smtpHost"].isNullOrBlank() }

    private val smtp: Mailer = SmtpMailer(this)

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

    /** [fragment], silent while the plugin recording it is off. */
    private fun owned(fragment: (ApplicationCall) -> String?): (ApplicationCall) -> String? {
        val owner = installing
        return { call -> if (enabled(owner)) fragment(call) else null }
    }

    /** [handler], skipped while the plugin recording it is off. */
    private fun owned(handler: suspend (ApplicationCall) -> Unit): suspend (ApplicationCall) -> Unit {
        val owner = installing
        return { call -> if (enabled(owner)) handler(call) }
    }

    /** `$name` in every page; with [blocks], in every `%` block too — what a WordPress shortcode is. */
    fun tool(name: String, value: Any, blocks: Boolean = false) {
        tools[name] = Owned(installing, value)
        if (blocks) blockTools += name
    }

    /**
     * `$name` in every page, one value per request, made on first use by [provider] and kept for that request
     * — state a page sets and a fragment reads back later in the same render (`$page.description("…")` before
     * `$site.head()`). With [blocks], blocks see it too.
     */
    fun requestTool(name: String, blocks: Boolean = false, provider: (ApplicationCall) -> Any) {
        requestTools[name] = Owned(installing, provider)
        if (blocks) blockTools += name
    }

    /** The value [requestTool] gave [name] for this [call], made now if it was not yet — null while its plugin is off. */
    fun requestValue(name: String, call: ApplicationCall): Any? {
        val provider = requestTools[name]?.takeIf { enabled(it.owner) }?.value ?: return null
        @Suppress("UNCHECKED_CAST")
        val key = requestKeys.computeIfAbsent(name) { AttributeKey<Any>("kroom.tool.$it") } as AttributeKey<Any>
        return call.attributes.computeIfAbsent(key) { provider(call) }
    }

    private val requestKeys = java.util.concurrent.ConcurrentHashMap<String, AttributeKey<*>>()

    /** Routes of the plugin's own: public endpoints (a form's submit, a webhook) or guarded ones ([can]). */
    fun routes(block: Route.() -> Unit) { routes += Owned(installing, block) }

    /** Runs before routing, for every request; answering the call ends it there (redirects, firewall, cache). */
    fun intercept(handler: suspend (ApplicationCall) -> Unit) { interceptors += owned(handler) }

    /**
     * Runs for a request nothing answered — no route, no page — before it becomes a 404: log it, or answer it
     * (a redirect learned too late for [intercept]). The first handler to answer ends it.
     */
    fun notFound(handler: suspend (ApplicationCall) -> Unit) { notFoundHandlers += owned(handler) }

    /** A fragment for `<head>` (meta, links, scripts); null or empty adds nothing. */
    fun head(fragment: (ApplicationCall) -> String?) { heads += owned(fragment) }

    /** A fragment for the end of `<body>`. */
    fun foot(fragment: (ApplicationCall) -> String?) { foots += owned(fragment) }

    /** An entry of the admin bar, shown to whoever holds its permission. */
    fun admin(entry: AdminEntry) { entries += Owned(installing, entry) }

    /** Called after each block an author submits — off the request: a slow listener delays nobody. */
    fun onPublish(listener: suspend (Block, UserSession) -> Unit) {
        val owner = installing
        publishListeners += { block, author -> if (enabled(owner)) listener(block, author) }
    }

    /** Every [period], from the site's start until it stops; a failing run is logged, the next one still comes. */
    fun every(period: Duration, job: suspend () -> Unit) {
        val owner = installing
        jobs += period to { if (enabled(owner)) job() }
    }

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
        else (builtinEntries.filter { it.id != "themes" || themes.size > 1 } + entries.filter { enabled(it.owner) }.map { it.value })
            .filter { can(session, it.permission) }

    private val builtinEntries = listOf(
        AdminEntry("site", "site", tables = listOf(AdminTable("rules", "Redirects", "$apiPrefix/rules"), AdminTable("missing", "Not found", "$apiPrefix/missing"))),
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
        // the editor's language, and the plugins' words the application translated
        val words = authoring?.assets?.stringTags("kroomAdmin", this.words).orEmpty()
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

/** Something a plugin recorded on the site — [owner] null for the site's own. */
class Owned<T>(val owner: Plugin?, val value: T)

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

    // --- what the site says of itself (the *site* settings) ------------------------------------------

    /** Every site setting by key, kroom's and the application's, defaults showing through. */
    val settings: Map<String, String> get() = site.settings().all()

    val name: String get() = site.settings()["name"].orEmpty()
    val lang: String get() = site.settings()["lang"].orEmpty()
    /** The public URL, no trailing slash; empty when unset. */
    val baseUrl: String get() = site.baseUrl
    val description: String get() = site.settings()["description"].orEmpty()
    val image: String get() = site.settings()["image"].orEmpty()
}

/** What every site is asked, in the groups the admin bar shows them in. */
internal val SITE_SETTINGS = listOf(
    Setting.text("name", "Name", default = "kroom", group = "Site"),
    Setting.text("lang", "Language", default = "en", help = "The content's — html lang", group = "Site"),
    Setting.text("baseUrl", "Public URL", help = "https://example.org — absolute URLs need it", group = "Site"),
    Setting.textarea("description", "Description", help = "What the site is, in a sentence or two", group = "Site"),
    Setting.text("image", "Picture", help = "Absolute URL of the picture a shared link shows", group = "Site"),
    Setting.text("smtpHost", "SMTP host", help = "Nothing is sent while empty", group = "Mail"),
    Setting.number("smtpPort", "SMTP port", default = 587, group = "Mail"),
    Setting.choice("smtpSecurity", "Security", choices = listOf("starttls", "tls", "none"), group = "Mail"),
    Setting.text("smtpUser", "User", group = "Mail"),
    Setting.secret("smtpPassword", "Password", group = "Mail"),
    Setting.text("mailFrom", "Sender", help = "Site <noreply@example.org>", group = "Mail"),
    Setting.textarea("redirects", "Rules", group = "Redirects",
        help = "One per line: /from /to [301|302|307|308] — a trailing * on both sides, ?id={id}")
)

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
