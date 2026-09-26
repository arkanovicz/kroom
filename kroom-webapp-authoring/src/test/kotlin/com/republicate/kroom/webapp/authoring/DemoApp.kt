package com.republicate.kroom.webapp.authoring

import com.republicate.kroom.webapp.assets.KroomAssets
import com.republicate.kroom.webapp.session.UserSession
import com.republicate.kroom.webapp.velocity.respondVelocity
import com.republicate.kroom.webapp.velocity.velocity
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set

/**
 * The demo, as a thing you can click: `./gradlew :kroom-webapp-authoring:demo` (`-Pport=9000` for another
 * port), then the URL it prints — log in (admin / admin, editor / editor), edit a block, submit, log out to read it as a
 * visitor does.
 *
 * Same call an application makes ([installContentSite]), a memory store that remembers its revisions, and
 * nothing else. [DemoSiteTest] asserts this same flow without the browser.
 */
fun main() {
    val port = System.getProperty("port")?.toIntOrNull() ?: 8088
    println("\n  kroom authoring demo — http://localhost:$port/login (admin / admin, or editor / editor)\n")
    embeddedServer(Netty, port = port) { demo() }.start(wait = true)
}

fun Application.demo() {
    val storage = MemoryStorage()
    storage.content.apply {
        write("pages/club/13Ma/description.md",
            "## Les Vagabonds\n\nOn joue le **mardi soir**, salle Jean Moulin.\n\n- débutants bienvenus\n- plateaux 19x19 fournis",
            mapOf("author" to "admin"))
    }
    installContentSite {
        this.storage = storage
        identity = MemoryIdentityProvider()
            .user("admin", "admin", Roles.ADMIN)
            .user("editor", "editor", Roles.EDITOR)
        loginPage = "pages/login.html"
        sessionSecret = "demo-only-secret"
        placeholder = "*Nothing here yet.*"
    }
    velocity.registerApplication("kroomAssets") { KroomAssets }
    velocity.registerApplication("demoStore") { storage.content }

    routing {
        get("/") { call.respondRedirect("/club/13Ma") }
    }
}
