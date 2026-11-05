package com.republicate.kroom.webapp.core

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

/**
 * Mount static routes for serving CSS, JS, images, fonts, sounds and other static assets.
 *
 * In production mode: assets loaded from classpath resources under /static/
 * In dev mode: assets loaded from the filesystem first (each dev dir in order), falling back to classpath, never cached by the browser
 */
fun Route.staticRoutes(config: StaticConfig = StaticConfig()) {
    val devDirs = if (config.devMode) config.devDirs else emptyList()
    if (devDirs.isNotEmpty()) {
        println("Dev mode: serving static files from ${devDirs.joinToString { it.absolutePath }}")
    }

    config.prefixes.forEach { prefix ->
        route("/$prefix") {
            get("/{path...}") {
                val path = call.parameters.getAll("path")?.joinToString("/")
                serveStatic(prefix, path, devDirs)
            }
        }
    }
}

private suspend fun RoutingContext.serveStatic(prefix: String, path: String?, devDirs: List<File>) {
    if (path.isNullOrBlank()) {
        call.respond(HttpStatusCode.NotFound)
        return
    }

    // Dev mode: the first dir holding the file, else the classpath
    devDirs.map { File(it, "$prefix/$path") }.firstOrNull { it.isFile }?.let { file ->
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondBytes(file.readBytes(), contentTypeFor(path))
        return
    }

    // Production: serve from classpath
    val resourcePath = "static/$prefix/$path"
    val resource = Thread.currentThread().contextClassLoader.getResourceAsStream(resourcePath)

    if (resource == null) {
        call.respond(HttpStatusCode.NotFound)
        return
    }

    val contentType = contentTypeFor(path)

    // Long cache (1 year) when versioned (?v=...), short cache otherwise; none in dev, where a rebuild changes it
    val hasVersion = call.request.queryParameters.contains("v")
    val maxAge = if (hasVersion) 31536000 else 3600  // 1 year vs 1 hour
    call.response.header(HttpHeaders.CacheControl, if (devDirs.isNotEmpty()) "no-cache" else "public, max-age=$maxAge")
    call.respondBytes(resource.readBytes(), contentType)
}

private fun contentTypeFor(path: String): ContentType = when {
    path.endsWith(".css") -> ContentType.Text.CSS
    path.endsWith(".js") -> ContentType.Application.JavaScript
    path.endsWith(".json") -> ContentType.Application.Json
    path.endsWith(".svg") -> ContentType.Image.SVG
    path.endsWith(".png") -> ContentType.Image.PNG
    path.endsWith(".jpg") || path.endsWith(".jpeg") -> ContentType.Image.JPEG
    path.endsWith(".gif") -> ContentType.Image.GIF
    path.endsWith(".mp3") -> ContentType.Audio.MPEG
    path.endsWith(".woff") -> ContentType("font", "woff")
    path.endsWith(".woff2") -> ContentType("font", "woff2")
    path.endsWith(".ttf") -> ContentType("font", "ttf")
    path.endsWith(".otf") -> ContentType("font", "otf")
    else -> ContentType.Application.OctetStream
}
