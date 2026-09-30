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
 * What the admin bar reads, mounted under [Site.apiPrefix], all for [Permissions.ADMIN]:
 *
 * ```
 * GET {prefix}/settings                the site's settings (a secret never read back)
 * PUT {prefix}/settings                {key: value} — declared keys only; "" keeps a secret, null resets
 * GET {prefix}/pages                   every page template, its route, and the pages its blocks say exist
 * GET {prefix}/plugins                 the plugins, each with its settings (a secret never read back)
 * PUT {prefix}/plugins/{id}/settings   {key: value} — declared keys only; "" keeps a secret, null resets
 * GET {prefix}/roles                   what each role may do, and the caller's roles
 * GET {prefix}/themes                  the installed themes, their layouts, which one is active
 * PUT {prefix}/theme                   {id} — what visitors get from now on
 * ```
 */
fun Route.siteRoutes() {
    val site = application.site

    route(site.apiPrefix) {

        get("/pages") {
            admin(site) ?: return@get
            respondJson(Json.MutableArray().apply {
                site.pages().forEach { page ->
                    push(Json.MutableObject().apply {
                        set("template", page.template)
                        set("route", page.route)
                        set("urls", Json.MutableArray().apply { page.urls.forEach { push(it) } })
                    })
                }
            })
        }

        get("/settings") {
            admin(site) ?: return@get
            respondJson(settingsJson(site.declaredSettings, site.settings()))
        }

        put("/settings") {
            admin(site) ?: return@put
            if (writeSettings("site", site.declaredSettings, site.settings())) respondSuccess()
        }

        get("/plugins") {
            admin(site) ?: return@get
            respondJson(Json.MutableArray().apply {
                site.plugins.forEach { plugin ->
                    push(Json.MutableObject().apply {
                        set("id", plugin.id)
                        set("name", plugin.name)
                        set("description", plugin.description)
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
internal suspend fun RoutingContext.admin(site: Site): UserSession? {
    val session = call.userSession
    if (session == null) {
        respondError("not authenticated", HttpStatusCode.Unauthorized, "notAuthenticated")
        return null
    }
    if (!site.can(session, Permissions.ADMIN)) {
        respondError("not an administrator", HttpStatusCode.Forbidden, "notAdmin")
        return null
    }
    return session
}
