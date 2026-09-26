package com.republicate.kroom.webapp.authoring

import com.republicate.kroom.plugins.analytics.Analytics
import com.republicate.kroom.plugins.forms.Forms
import com.republicate.kroom.plugins.linkcheck.LinkCheck
import com.republicate.kroom.plugins.mail.Mail
import com.republicate.kroom.plugins.redirects.Redirects
import com.republicate.kroom.plugins.seo.Seo
import com.republicate.kroom.plugins.webhook.Webhook
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
 * The demo, as a thing you can click: `docker compose -f kroom-webapp-authoring/demo/compose.yml up` (with a
 * mailbox), or `./gradlew :kroom-webapp-authoring:demo` (`-Pport=9000` for another port), then the URL it prints — log in (admin / admin, editor / editor), edit a block, submit, log out to read it as a
 * visitor does. As admin, the bar on the left lists the pages, the journal, the plugins and their settings.
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
        // an admin sees them in the bar on the left; a block may call `$forms.contact()`
        plugins += listOf(Seo(), Redirects(), Forms(), Analytics(), Webhook(), Mail(), LinkCheck())
    }

    // dockerized (demo/compose.yml), mail goes to Mailpit, and each form message to the admin
    System.getenv("KROOM_SMTP")?.let { smtp ->
        storage.settings("mail").apply {
            set("host", smtp.substringBefore(':'))
            set("port", smtp.substringAfter(':', "25"))
            set("security", "none")
            set("from", "kroom demo <demo@kroom.test>")
            set("inbox", System.getenv("KROOM_INBOX"))
        }
        storage.settings("forms")["notify"] = "admin@kroom.test"
    }
    velocity.registerApplication("kroomAssets") { KroomAssets }
    velocity.registerApplication("demoStore") { storage.content }

    routing {
        get("/") { call.respondRedirect("/club/13Ma") }
    }
}
