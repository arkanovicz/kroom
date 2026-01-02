package com.republicate.kroom.webapp.core

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Mount static routes for serving CSS, JS, images, sounds and other static assets.
 *
 * Assets are loaded from classpath resources under /static/
 *
 * Serves:
 * - /css/{path} from static/css/
 * - /js/{path} from static/js/
 * - /img/{path} from static/img/
 * - /lib/{path} from static/lib/
 * - /snd/{path} from static/snd/
 */
fun Route.staticRoutes() {
    listOf("css", "js", "img", "lib", "snd").forEach { prefix ->
        route("/$prefix") {
            get("/{path...}") {
                serveStatic(prefix, call.parameters.getAll("path")?.joinToString("/"))
            }
        }
    }
}

private suspend fun RoutingContext.serveStatic(prefix: String, path: String?) {
    if (path.isNullOrBlank()) {
        call.respond(HttpStatusCode.NotFound)
        return
    }

    val resourcePath = "static/$prefix/$path"
    val resource = Thread.currentThread().contextClassLoader.getResourceAsStream(resourcePath)

    if (resource == null) {
        call.respond(HttpStatusCode.NotFound)
        return
    }

    val contentType = when {
        path.endsWith(".css") -> ContentType.Text.CSS
        path.endsWith(".js") -> ContentType.Application.JavaScript
        path.endsWith(".json") -> ContentType.Application.Json
        path.endsWith(".svg") -> ContentType.Image.SVG
        path.endsWith(".png") -> ContentType.Image.PNG
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> ContentType.Image.JPEG
        path.endsWith(".mp3") -> ContentType.Audio.MPEG
        path.endsWith(".woff") -> ContentType("font", "woff")
        path.endsWith(".woff2") -> ContentType("font", "woff2")
        path.endsWith(".ttf") -> ContentType("font", "ttf")
        else -> ContentType.Application.OctetStream
    }

    call.respondBytes(resource.readBytes(), contentType)
}
