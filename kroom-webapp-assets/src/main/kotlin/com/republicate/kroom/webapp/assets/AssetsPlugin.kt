package com.republicate.kroom.webapp.assets

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Kroom webapp assets plugin.
 *
 * Provides shared client-side assets:
 * - domhelper.js - lightweight jQuery-like DOM helper
 * - api.js - fetch wrapper for REST APIs
 * - store.js - minimal Redux-like state management
 * - markdown.js, markdown.css - the markdown toolbar
 * - text.css - the parts of a text written by several
 *
 * Usage:
 * ```kotlin
 * routing {
 *     kroomAssets()           // serves at /js/kroom/...
 *     kroomAssets("/assets")  // serves at /assets/js/...
 * }
 * ```
 */
fun Route.kroomAssets(prefix: String = "") {
    val basePath = prefix.trimEnd('/')

    route("$basePath/js/kroom") {
        get("/{file}") {
            val file = call.parameters["file"] ?: return@get call.respond(HttpStatusCode.NotFound)
            serveAsset("js/$file", call)
        }
    }

    route("$basePath/css/kroom") {
        get("/{file}") {
            val file = call.parameters["file"] ?: return@get call.respond(HttpStatusCode.NotFound)
            serveAsset("css/$file", call)
        }
    }

    route("$basePath/lib") {
        get("/{path...}") {
            val path = call.parameters.getAll("path")?.joinToString("/")
            if (path.isNullOrBlank()) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            serveAsset("lib/$path", call)
        }
    }
}

private suspend fun serveAsset(path: String, call: ApplicationCall) {
    val resourcePath = "static/$path"  // assets are packaged under static/ (matches installCore)
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
        path.endsWith(".woff") -> ContentType("font", "woff")
        path.endsWith(".woff2") -> ContentType("font", "woff2")
        path.endsWith(".ttf") -> ContentType("font", "ttf")
        path.endsWith(".map") -> ContentType.Application.Json
        else -> ContentType.Application.OctetStream
    }

    call.respondBytes(resource.readBytes(), contentType)
}

/**
 * Version info for cache busting
 */
object KroomAssets {
    /**
     * The build's version (generated from `project.version`). A snapshot's files change from one run to the next
     * under one version: it also says when this run started, or a browser keeps yesterday's script for its hour.
     */
    val VERSION: String = if (KROOM_VERSION.endsWith("-SNAPSHOT")) "$KROOM_VERSION-${System.currentTimeMillis().toString(36)}" else KROOM_VERSION

    /** Script tag for domhelper.js */
    fun domhelperScript(prefix: String = "") = """<script src="${prefix.trimEnd('/')}/js/kroom/domhelper.js?v=$VERSION"></script>"""

    /** Script tag for api.js */
    fun apiScript(prefix: String = "") = """<script src="${prefix.trimEnd('/')}/js/kroom/api.js?v=$VERSION"></script>"""

    /** Script tag for store.js */
    fun storeScript(prefix: String = "") = """<script src="${prefix.trimEnd('/')}/js/kroom/store.js?v=$VERSION"></script>"""

    /** Script tag for sse.js */
    fun sseScript(prefix: String = "") = """<script src="${prefix.trimEnd('/')}/js/kroom/sse.js?v=$VERSION"></script>"""

    /** Script tag for markdown.js, the markdown toolbar (after domhelper.js) */
    fun markdownScript(prefix: String = "") = """<script src="${prefix.trimEnd('/')}/js/kroom/markdown.js?v=$VERSION"></script>"""

    /** Stylesheet tag for markdown.css, the toolbar's and the pictograms' rules */
    fun markdownStyle(prefix: String = "") = """<link rel="stylesheet" href="${prefix.trimEnd('/')}/css/kroom/markdown.css?v=$VERSION">"""

    /** Stylesheet tag for text.css, the parts of a text written by several (an element of class kroom-text) */
    fun textStyle(prefix: String = "") = """<link rel="stylesheet" href="${prefix.trimEnd('/')}/css/kroom/text.css?v=$VERSION">"""

    /** All core scripts in order (domhelper, api, store, sse) */
    fun coreScripts(prefix: String = "") = listOf(
        domhelperScript(prefix),
        apiScript(prefix),
        storeScript(prefix),
        sseScript(prefix)
    ).joinToString("\n")
}
