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
 * GET {prefix}/pages                   every page template, its route, and the pages its blocks say exist
 * GET {prefix}/plugins                 the plugins, each with its settings (a secret never read back)
 * PUT {prefix}/plugins/{id}/settings   {key: value} — declared keys only; "" keeps a secret, null resets
 * GET {prefix}/roles                   what each role may do, and the caller's roles
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

        get("/plugins") {
            admin(site) ?: return@get
            respondJson(Json.MutableArray().apply {
                site.plugins.forEach { plugin ->
                    val values = site.settings(plugin)
                    push(Json.MutableObject().apply {
                        set("id", plugin.id)
                        set("name", plugin.name)
                        set("description", plugin.description)
                        set("settings", Json.MutableArray().apply {
                            plugin.settings.forEach { setting ->
                                push(Json.MutableObject().apply {
                                    set("key", setting.key)
                                    set("label", setting.label)
                                    set("type", setting.type.name.lowercase())
                                    setting.help?.let { set("help", it) }
                                    if (setting.choices.isNotEmpty()) set("choices", Json.MutableArray().apply { setting.choices.forEach { push(it) } })
                                    if (setting.type == Setting.Type.SECRET) set("set", !values[setting.key].isNullOrEmpty())
                                    else set("value", values[setting.key])
                                })
                            }
                        })
                    })
                }
            })
        }

        put("/plugins/{id}/settings") {
            admin(site) ?: return@put
            val id = call.parameters["id"]!!
            val plugin = site.plugin(id)
                ?: return@put respondError("no plugin $id", HttpStatusCode.NotFound, "noPlugin", mapOf("id" to id))
            val asked = receiveJsonObject()
            val declared = plugin.settings.associateBy { it.key }
            asked.keys.firstOrNull { it !in declared }?.let { key ->
                return@put respondError("$id has no setting $key", code = "noSetting", args = mapOf("key" to key))
            }
            val settings = site.settings(plugin)
            asked.entries.firstOrNull { (key, value) -> declared[key]!!.let { it.type == Setting.Type.CHOICE && value != null && value.toString() !in it.choices } }?.let { (key, value) ->
                return@put respondError("$value is not a choice of $key", code = "noChoice", args = mapOf("key" to key, "value" to value))
            }
            for ((key, value) in asked) {
                val text = value?.toString()
                if (declared[key]!!.type == Setting.Type.SECRET && text == "") continue
                settings[key] = text
            }
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

/** The caller if an admin, or null having answered 401/403. */
private suspend fun RoutingContext.admin(site: Site): UserSession? {
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
