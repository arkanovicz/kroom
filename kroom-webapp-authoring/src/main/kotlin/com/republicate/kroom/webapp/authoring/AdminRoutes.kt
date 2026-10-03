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
 * POST {prefix}/pages                  {path, label, layout} — a new authored page, a draft
 * POST {prefix}/pages/move             {from, to} — an authored page without pages under it changes place, blocks and past with it
 * PUT {prefix}/pages/{path}            what changes: layout, status (draft|published)
 * DELETE {prefix}/pages/{path}         the record goes; its blocks stay in the store
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
            // an editor's view: a draft resolves (they may see it), what is missing does not
            fun state(path: String) = if (path in served) PageState.PUBLISHED else site.authored.get(path)?.let { PageState.DRAFT } ?: PageState.MISSING
            fun entry(item: MenuItem, parent: String): Json.MutableObject = item.toJson().apply {
                val path = "$parent/${item.slug}"
                set("path", path)
                set("kind", kind(path))
                site.authored.get(path)?.let { page ->
                    set("status", page.status)
                    set("layout", page.layout)
                    // v1 moves leaves only
                    set("movable", item.children.isEmpty())
                }
                set("resolved", state(path) != PageState.MISSING)
                set("children", Json.MutableArray().apply { item.children.forEach { push(entry(it, path)) } })
            }
            respondJson {
                set("stored", site.storedMenu() != null)
                set("lang", lang)
                set("languages", Json.MutableArray().apply { site.languages.forEach { push(it) } })
                set("layouts", Json.MutableArray().apply { SKELETON_LAYOUTS.forEach { push(it) } })
                set("items", Json.MutableArray().apply { site.menu().forEach { push(entry(it, "")) } })
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
            if (site.authored.taken(path))
                return@post respondError("a page already answers $path", HttpStatusCode.Conflict, "pageExists", mapOf("path" to path))
            site.authored.put(page)
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
            site.authored.put(page)
            respondJson(page.toJson())
        }

        delete("/pages/{path...}") {
            val path = "/" + call.parameters.getAll("path").orEmpty().joinToString("/")
            allowed(site, Permissions.PAGE_EDIT, path) ?: return@delete
            if (!site.authored.delete(path))
                return@delete respondError("no authored page at $path", HttpStatusCode.NotFound, "noPage", mapOf("page" to path))
            respondSuccess()
        }

        get("/plugins") {
            admin(site) ?: return@get
            respondJson(Json.MutableArray().apply {
                site.plugins.forEach { plugin ->
                    push(Json.MutableObject().apply {
                        set("id", plugin.id)
                        set("name", plugin.name)
                        set("description", plugin.description)
                        set("enabled", site.enabled(plugin))
                        if (plugin is Theme) set("theme", true)       // switched on the themes panel, not here
                        set("settings", settingsJson(plugin.settings, site.settings(plugin)))
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
