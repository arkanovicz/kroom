package com.republicate.kroom.webapp.authoring

import com.republicate.kroom.webapp.core.receiveJsonObject
import com.republicate.kroom.webapp.core.respondError
import com.republicate.kroom.webapp.core.respondJson
import com.republicate.kroom.webapp.core.respondSuccess
import com.republicate.kroom.webapp.session.UserSession
import com.republicate.kroom.webapp.session.userSession
import com.republicate.kson.Json
import io.ktor.http.*
import io.ktor.server.routing.*

/**
 * What the admin bar reads, mounted under [Site.apiPrefix] — for [Permissions.ADMIN], the pages for [Permissions.PAGE_EDIT]:
 *
 * ```
 * GET {prefix}/settings                the site's settings (a secret never read back)
 * PUT {prefix}/settings                {key: value} — declared keys only; "" keeps a secret, null resets
 * GET {prefix}/menu                    the pages as a tree — the menu: each entry's path, kind, status, whether it may move
 * PUT {prefix}/menu                    {items} — their order and words; DELETE forgets the arrangement
 * POST {prefix}/pages                  {path, label, layout, status} — a new authored page, a draft unless said, under a page (409 noParent)
 * POST {prefix}/pages/move             {from, to} — an authored page without pages under it changes place, blocks and past with it, under a page
 * PUT {prefix}/pages/{path}            what changes: layout, status (draft|published) — published publishes the drafts above,
 *                                      draft is refused while visitors reach a page under it (409 pageHolds)
 * DELETE {prefix}/pages/{path}         the record goes, unless pages live under it (409 pageHolds); its blocks stay in the store
 * GET {prefix}/plugins                 the plugins, each with its settings (a secret never read back)
 * PUT {prefix}/plugins/{id}/settings   {key: value} — declared keys only; "" keeps a secret, null resets
 * PUT {prefix}/plugins/{id}/enabled    {enabled} — live; enabling asks the plugin's `check`, 409 with its answer
 * GET {prefix}/roles                   what each role may do, and the caller's roles
 * GET {prefix}/themes                  the installed themes, their layouts, which one is active
 * PUT {prefix}/theme                   {id} — what visitors get from now on
 * ```
 */
fun Route.siteRoutes() {
    val site = application.site

    route(site.apiPrefix) {

        get("/settings") {
            admin(site) ?: return@get
            respondJson(settingsJson(site.declaredSettings, site.settings()))
        }

        put("/settings") {
            admin(site) ?: return@put
            if (writeSettings("site", site.declaredSettings, site.settings())) respondSuccess()
        }

        // the pages as a tree, which is the menu: what exists, arranged and worded as stored
        get("/menu") {
            allowed(site, Permissions.PAGE_EDIT) ?: return@get
            val served = site.urls().toSet()
            val lang = site.defaultLanguage
            val kind = site.kinds()
            // an editor's view: a draft is there (they may see it); a path no page answers is no entry, nor what is under it
            fun there(path: String) = path in served || site.authored.get(path) != null
            fun entry(item: MenuItem, parent: String): Json.MutableObject? = if (!item.external && !there("$parent/${item.slug}")) null else item.toJson().apply {
                if (item.external) {
                    set("kind", "link")
                    set("movable", true)
                    return@apply
                }
                val path = "$parent/${item.slug}"
                set("path", path)
                set("kind", kind(path))
                site.authored.get(path)?.let { page ->
                    set("status", page.status)
                    set("layout", page.layout)
                    // v1 moves leaves only — and what a developer's template lays out stays under it
                    set("movable", item.children.isEmpty() && !site.authored.templated(path))
                }
                set("children", Json.MutableArray().apply { item.children.forEach { child -> entry(child, path)?.let { push(it) } } })
            }
            respondJson {
                set("stored", site.storedMenu() != null)
                set("lang", lang)
                set("languages", Json.MutableArray().apply { site.languages.forEach { push(it) } })
                set("layouts", Json.MutableArray().apply { SKELETON_LAYOUTS.forEach { push(it) } })
                set("items", Json.MutableArray().apply { site.menu().forEach { item -> entry(item, "")?.let { push(it) } } })
            }
        }

        put("/menu") {
            allowed(site, Permissions.PAGE_EDIT) ?: return@put
            val items = receiveJsonObject().getArray("items")
                ?: return@put respondError("items expected", code = "menuInvalid", args = mapOf("message" to "items expected"))
            val parsed = MenuItem.parseAll(items).getOrElse { e ->
                return@put respondError(e.message ?: "invalid menu", code = "menuInvalid", args = mapOf("message" to (e.message ?: "")))
            }
            site.storeMenu(parsed)
            respondSuccess()
        }

        delete("/menu") {
            allowed(site, Permissions.PAGE_EDIT) ?: return@delete
            site.storeMenu(null)
            respondSuccess()
        }

        post("/pages") {
            val asked = receiveJsonObject()
            val path = asked.getString("path") ?: return@post respondError("path expected", code = "pageInvalid", args = mapOf("message" to "path expected"))
            val session = allowed(site, Permissions.PAGE_EDIT, path) ?: return@post
            val page = AuthoredPage.parse(asked).getOrElse { e ->
                return@post respondError(e.message ?: "invalid page", code = "pageInvalid", args = mapOf("message" to (e.message ?: "")))
            }.copy(author = session.id)
            if (site.authored.occupied(path))
                return@post respondError("a page already answers $path", HttpStatusCode.Conflict, "pageExists", mapOf("path" to path))
            site.authored.parent(path)?.takeIf { !site.authored.taken(it) }?.let { above ->
                return@post respondError("no page at $above: a page goes under a page", HttpStatusCode.Conflict, "noParent", mapOf("parent" to above))
            }
            site.authored.put(page)
            if (page.isPublished) site.authored.publishAbove(path)
            site.authored.seed(page, session.id)
            // its words are its menu entry's: the label it was given is stored there
            asked.getObject("label")?.entries?.associate { (k, v) -> k to v.toString() }?.filterValues { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
                ?.let { label -> site.rewrite(path) { it.copy(label = label) } }
            respondJson(page.toJson())
        }

        post("/pages/move") {
            val asked = receiveJsonObject()
            val from = asked.getString("from").orEmpty()
            val to = asked.getString("to").orEmpty()
            val session = allowed(site, Permissions.PAGE_EDIT, from) ?: return@post
            if (!site.can(session, Permissions.PAGE_EDIT, to))
                return@post respondError("not allowed: ${Permissions.PAGE_EDIT}", HttpStatusCode.Forbidden, "notAllowed", mapOf("permission" to Permissions.PAGE_EDIT))
            site.authored.move(from, to, session.id)?.let { problem ->
                return@post respondError(problem, HttpStatusCode.Conflict, "pageMove", mapOf("message" to problem))
            }
            respondSuccess()
        }

        put("/pages/{path...}") {
            val path = "/" + call.parameters.getAll("path").orEmpty().joinToString("/")
            allowed(site, Permissions.PAGE_EDIT, path) ?: return@put
            val existing = site.authored.get(path)
                ?: return@put respondError("no authored page at $path", HttpStatusCode.NotFound, "noPage", mapOf("page" to path))
            val page = AuthoredPage.parse(receiveJsonObject(), existing).getOrElse { e ->
                return@put respondError(e.message ?: "invalid page", code = "pageInvalid", args = mapOf("message" to (e.message ?: "")))
            }
            // a page out under a draft would be in no menu
            if (existing.isPublished && !page.isPublished && site.urls().any { it.startsWith("$path/") })
                return@put respondError("$path has pages out under it", HttpStatusCode.Conflict, "pageHolds", mapOf("page" to path))
            site.authored.put(page)
            if (page.isPublished) site.authored.publishAbove(path)
            respondJson(page.toJson())
        }

        delete("/pages/{path...}") {
            val path = "/" + call.parameters.getAll("path").orEmpty().joinToString("/")
            allowed(site, Permissions.PAGE_EDIT, path) ?: return@delete
            if (site.authored.get(path) != null && site.authored.holds(path))
                return@delete respondError("$path has pages under it", HttpStatusCode.Conflict, "pageHolds", mapOf("page" to path))
            if (!site.authored.delete(path))
                return@delete respondError("no authored page at $path", HttpStatusCode.NotFound, "noPage", mapOf("page" to path))
            respondSuccess()
        }

        get("/plugins") {
            admin(site) ?: return@get
            respondJson(Json.MutableArray().apply {
                // a theme is chosen and set on the themes panel
                site.plugins.filter { it !is Theme }.forEach { plugin ->
                    push(Json.MutableObject().apply {
                        set("id", plugin.id)
                        set("name", plugin.name)
                        set("description", plugin.description)
                        set("enabled", site.enabled(plugin))
                        set("settings", settingsJson(plugin.settings, site.settings(plugin)))
                        site.tables(plugin).takeIf { it.isNotEmpty() }?.let { tables ->
                            set("tables", Json.MutableArray().apply {
                                tables.forEach { push(Json.MutableObject().apply { set("id", it.id); set("label", it.label); set("url", it.url) }) }
                            })
                        }
                        if (site.enabled(plugin)) plugin.holds(site)?.let { set("holds", it) }
                    })
                }
            })
        }

        put("/plugins/{id}/settings") {
            admin(site) ?: return@put
            val id = call.parameters["id"]!!
            val plugin = site.plugin(id)
                ?: return@put respondError("no plugin $id", HttpStatusCode.NotFound, "noPlugin", mapOf("id" to id))
            if (writeSettings(id, plugin.settings, site.settings(plugin))) respondSuccess()
        }

        put("/plugins/{id}/enabled") {
            admin(site) ?: return@put
            val id = call.parameters["id"]!!
            val plugin = site.plugin(id)
                ?: return@put respondError("no plugin $id", HttpStatusCode.NotFound, "noPlugin", mapOf("id" to id))
            val on = receiveJsonObject().getBoolean("enabled") ?: true
            site.enable(plugin, on)?.let { problem ->
                return@put respondError(problem, HttpStatusCode.Conflict, "pluginCheck", mapOf("id" to id, "message" to problem))
            }
            respondSuccess()
        }

        get("/themes") {
            val session = admin(site) ?: return@get
            val active = site.theme(call)
            respondJson(Json.MutableArray().apply {
                site.themes.forEach { theme ->
                    push(Json.MutableObject().apply {
                        set("id", theme.id)
                        set("name", theme.name)
                        set("description", theme.description)
                        set("layouts", Json.MutableArray().apply { theme.layouts.sorted().forEach { push(it) } })
                        set("active", theme == active)
                        // set before it is worn: the preview shows them (written like any plugin's, `plugins/{id}/settings`)
                        set("settings", settingsJson(theme.settings, site.settings(theme)))
                    })
                }
            })
        }

        put("/theme") {
            admin(site) ?: return@put
            val id = receiveJsonObject().getString("id")
            val theme = site.themes.firstOrNull { it.id == id }
                ?: return@put respondError("no theme $id", HttpStatusCode.NotFound, "noTheme", mapOf("id" to id))
            site.activate(theme)
            respondSuccess()
        }

        get("/roles") {
            val session = admin(site) ?: return@get
            respondJson {
                set("roles", Json.MutableObject().apply {
                    site.grants().forEach { (role, grants) -> set(role, Json.MutableArray().apply { grants.forEach { push(it) } }) }
                })
                set("yours", Json.MutableArray().apply { site.roles(session).sorted().forEach { push(it) } })
            }
        }
    }
}

/** [declared] settings with their [values] — a secret only says whether it is set. */
private fun settingsJson(declared: List<Setting>, values: Settings) = Json.MutableArray().apply {
    declared.forEach { setting ->
        push(Json.MutableObject().apply {
            set("key", setting.key)
            set("label", setting.label)
            set("type", setting.type)
            setting.help?.let { set("help", it) }
            setting.group?.let { set("group", it) }
            if (setting.choices.isNotEmpty()) set("choices", Json.MutableArray().apply { setting.choices.forEach { push(it) } })
            if (setting.type == "secret") set("set", !values[setting.key].isNullOrEmpty())
            else set("value", values[setting.key])
        })
    }
}

/**
 * The body's `{key: value}` into [settings], [declared] keys only — "" keeps a secret, null resets. False
 * having answered why not.
 */
private suspend fun RoutingContext.writeSettings(owner: String, declared: List<Setting>, settings: Settings): Boolean {
    val asked = receiveJsonObject()
    val known = declared.associateBy { it.key }
    asked.keys.firstOrNull { it !in known }?.let { key ->
        respondError("$owner has no setting $key", code = "noSetting", args = mapOf("key" to key))
        return false
    }
    asked.entries.firstOrNull { (key, value) -> known[key]!!.let { it.type == "choice" && value != null && value.toString() !in it.choices } }?.let { (key, value) ->
        respondError("$value is not a choice of $key", code = "noChoice", args = mapOf("key" to key, "value" to value))
        return false
    }
    for ((key, value) in asked) {
        val text = value?.toString()
        if (known[key]!!.type == "secret" && text == "") continue
        settings[key] = text
    }
    return true
}

/** The caller if an admin, or null having answered 401/403. */
internal suspend fun RoutingContext.admin(site: Site): UserSession? = allowed(site, Permissions.ADMIN)

/** The caller if [permission] is theirs, or null having answered 401/403. */
internal suspend fun RoutingContext.allowed(site: Site, permission: String, target: String = ""): UserSession? {
    val session = call.userSession
    if (session == null) {
        respondError("not authenticated", HttpStatusCode.Unauthorized, "notAuthenticated")
        return null
    }
    if (!site.can(session, permission, target)) {
        respondError("not allowed: $permission", HttpStatusCode.Forbidden, "notAllowed", mapOf("permission" to permission))
        return null
    }
    return session
}
