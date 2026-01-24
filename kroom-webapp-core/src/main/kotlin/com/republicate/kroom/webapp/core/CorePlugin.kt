package com.republicate.kroom.webapp.core

import io.ktor.http.*
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

    install(CallLogging) {
        level = config.logLevel
    }

    install(DefaultHeaders) {
        header("X-Engine", "Kroom")
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception", cause)
            call.respondText(
                text = cause.message ?: "Internal Server Error",
                status = HttpStatusCode.InternalServerError
            )
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondText("Not Found", status = HttpStatusCode.NotFound)
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
    var devDir: File? = null
}
