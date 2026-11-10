package com.republicate.kroom.webapp.core

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.event.Level
import java.io.File

/**
 * Install core Ktor plugins for webapp foundation.
 *
 * Includes:
 * - CallLogging (request logging)
 * - DefaultHeaders
 * - StatusPages (error handling)
 * - Static routes (configurable prefixes, dev/prod mode)
 */
fun Application.installCore(block: CoreConfig.() -> Unit = {}) {
    val config = CoreConfig().apply(block)

    // l10n redirects "/" to "/en/"; normalize trailing slashes once, for every kroom app
    install(IgnoreTrailingSlash)

    install(CallLogging) {
        level = config.logLevel
    }

    install(DefaultHeaders) {
        header("X-Engine", "Kroom")
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            // the message is the operator's: it can carry paths, template names, values
            call.application.log.error("Unhandled exception", cause)
            call.respondText("Internal Server Error", status = HttpStatusCode.InternalServerError)
        }
        // only a 404 nobody explained: one that carries a body (an API's JSON error) says why itself
        status(HttpStatusCode.NotFound) { _ ->
            if (content is OutgoingContent.NoContent) call.respondText("Not Found", status = HttpStatusCode.NotFound)
        }
    }

    routing {
        staticRoutes(config.staticConfig)
    }
}

class CoreConfig {
    var logLevel: Level = Level.INFO
    val staticConfig = StaticConfig()

    fun static(block: StaticConfig.() -> Unit) {
        staticConfig.apply(block)
    }
}

class StaticConfig {
    var prefixes: List<String> = listOf("css", "js", "img", "fonts", "lib", "snd")
    var devMode: Boolean = false
    /** Served live in dev mode, tried in order before the classpath: sources, then a build output (a Kotlin/JS bundle). */
    var devDirs: List<File> = emptyList()
    /** The one-directory shorthand. */
    var devDir: File?
        get() = devDirs.firstOrNull()
        set(value) { devDirs = listOfNotNull(value) }
}
