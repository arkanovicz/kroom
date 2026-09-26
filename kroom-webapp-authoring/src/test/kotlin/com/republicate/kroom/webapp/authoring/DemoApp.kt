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
 * port), then the URL it prints — log in (admin / admin), edit a block, submit, log out to read it as a
 * visitor does.
 *
 * Same call an application makes ([installContentSite]), a memory store that remembers its revisions, and
 * nothing else. [DemoSiteTest] asserts this same flow without the browser.
 */
fun main() {
    val port = System.getProperty("port")?.toIntOrNull() ?: 8088
    println("\n  kroom authoring demo — http://localhost:$port/login/admin (any name works)\n")
    embeddedServer(Netty, port = port) { demo() }.start(wait = true)
}

fun Application.demo() {
    val store = VersionedMemoryResourceStore().apply {
        write("pages/club/13Ma/description.md",
            "## Les Vagabonds\n\nOn joue le **mardi soir**, salle Jean Moulin.\n\n- débutants bienvenus\n- plateaux 19x19 fournis",
            mapOf("author" to "admin"))
    }
    installContentSite {
        this.store = store
        sessionSecret = "demo-only-secret"
        placeholder = "*Nothing here yet.*"
    }
    velocity.registerApplication("kroomAssets") { KroomAssets }
    velocity.registerApplication("demoStore") { store }

    routing {
        get("/") { call.respondRedirect("/club/13Ma") }

        // a login worth exactly what a demo needs: admin / admin, and the author it stamps on what you write;
        // the form itself is a page like any other, /login -> pages/login.html
        post("/login") {
            val form = call.receiveParameters()
            val who = form["user"].orEmpty()
            if (who == "admin" && form["password"] == "admin") {
                call.sessions.set(UserSession(who, who, null, "demo"))
                call.respondRedirect(form["from"] ?: "/")
            } else {
                call.respondVelocity("pages/login.html", mapOf("failed" to true))
            }
        }
        post("/logout") {
            call.sessions.clear<UserSession>()
            call.respondRedirect(call.request.headers["Referer"] ?: "/")
        }
    }
}
