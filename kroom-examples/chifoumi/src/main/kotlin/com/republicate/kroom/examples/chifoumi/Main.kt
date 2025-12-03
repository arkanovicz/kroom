package com.republicate.kroom.examples.chifoumi

import com.republicate.kroom.examples.chifoumi.routes.PlayerSession
import com.republicate.kroom.examples.chifoumi.routes.chifoumiPages
import com.republicate.kroom.examples.chifoumi.routes.chifoumiRoutes
import com.republicate.kroom.webapp.core.installCore
import com.republicate.kroom.webapp.l10n.installL10n
import com.republicate.kroom.webapp.velocity.installVelocity
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)
}

fun Application.module() {
    // Install core plugins
    installCore()

    // Install sessions
    install(Sessions) {
        cookie<PlayerSession>("chifoumi_session")
    }

    // Install Velocity templating
    installVelocity {
        templatePath = "templates"
    }

    // Install localization
    installL10n {
        sourceLanguage = "en"
        defaultLanguage = "en"
        language("en", "English")
        language("fr", "Français")
        i18nPath = "/i18n"
    }

    // Configure routes
    routing {
        // Game routes (API + SSE)
        chifoumiRoutes()

        // Language-prefixed page routes
        route("/{lang}") {
            chifoumiPages()
        }
    }

    log.info("Chifoumi Arena started at http://localhost:8080")
}
