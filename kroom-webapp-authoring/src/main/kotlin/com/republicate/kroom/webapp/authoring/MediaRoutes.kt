package com.republicate.kroom.webapp.authoring

import com.republicate.kroom.webapp.core.respondError
import com.republicate.kroom.webapp.core.respondJson
import com.republicate.kroom.webapp.core.respondSuccess
import com.republicate.kroom.webapp.session.userSession
import com.republicate.kson.Json
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray

/**
 * ```
 * GET    {mediaPrefix}/{name}          the file, to anyone: pages show it
 * POST   {apiPrefix}/media?name=…      the raw bytes as body — `content.upload`; 413 too large, 415 not a
 *                                      picture or a PDF (told by its bytes), 507 no room left
 * GET    {apiPrefix}/media             what was uploaded, newest first — `content.upload`
 * DELETE {apiPrefix}/media/{name}      `site.admin`: a page may still show it
 * ```
 */
fun Route.mediaRoutes() {
    val plugin = application.authoring
    val media = requireNotNull(plugin.media)

    get("${plugin.mediaPrefix}/{name}") {
        val name = mediaNameOrNull(call.parameters["name"]!!) ?: return@get call.respond(HttpStatusCode.NotFound)
        val file = media.get(name) ?: return@get call.respond(HttpStatusCode.NotFound)
        val bytes = media.read(name) ?: return@get call.respond(HttpStatusCode.NotFound)
        // a name is never reused: the file under it never changes
        call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.respondBytes(bytes, ContentType.parse(file.type))
    }

    route("${plugin.apiPrefix}/media") {
        post {
            if (!allowed(plugin, Permissions.UPLOAD)) return@post
            val bytes = call.receiveChannel().readRemaining(plugin.mediaMaxSize + 1).readByteArray()
            if (bytes.size > plugin.mediaMaxSize)
                return@post respondError("larger than ${plugin.mediaMaxSize} bytes", HttpStatusCode.PayloadTooLarge,
                    "mediaTooLarge", mapOf("max" to plugin.mediaMaxSize))
            val type = MediaTypes.sniff(bytes)
                ?: return@post respondError("not a picture nor a PDF", HttpStatusCode.UnsupportedMediaType, "mediaType")
            val file = try {
                media.put(call.request.queryParameters["name"] ?: "file", type, bytes)
            } catch (e: MediaFullException) {
                return@post respondError("no room left for media", HttpStatusCode.InsufficientStorage, "mediaFull")
            }
            respondJson(json(plugin, file))
        }

        get {
            if (!allowed(plugin, Permissions.UPLOAD)) return@get
            respondJson(Json.MutableArray().apply { media.list(call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).forEach { push(json(plugin, it)) } })
        }

        delete("/{name}") {
            if (!allowed(plugin, Permissions.ADMIN)) return@delete
            if (!media.delete(call.parameters["name"]!!)) return@delete call.respond(HttpStatusCode.NotFound)
            respondSuccess()
        }
    }
}

private fun json(plugin: AuthoringPlugin, file: MediaFile) = Json.MutableObject().apply {
    set("name", file.name)
    set("url", "${plugin.mediaPrefix}/${file.name}")
    set("type", file.type)
    set("size", file.size)
    set("time", file.time)
}

/** Whether the caller holds [permission], having answered 401/403 when not. */
private suspend fun RoutingContext.allowed(plugin: AuthoringPlugin, permission: String): Boolean {
    val session = call.userSession
    when {
        session == null -> respondError("not authenticated", HttpStatusCode.Unauthorized, "notAuthenticated")
        !plugin.can(session, permission) -> respondError("not allowed: $permission", HttpStatusCode.Forbidden, "notAllowed", mapOf("permission" to permission))
        else -> return true
    }
    return false
}
