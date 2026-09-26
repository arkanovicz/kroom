package com.republicate.kroom.plugins.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import com.republicate.kroom.webapp.authoring.IdentityProvider
import com.republicate.kroom.webapp.authoring.MemoryStorage
import com.republicate.kroom.webapp.authoring.Roles
import com.republicate.kroom.webapp.authoring.installContentSite
import com.republicate.kroom.webapp.authoring.site
import com.republicate.kroom.webapp.session.UserSession
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.testing.*
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MailTest {

    private val smtp = GreenMail(ServerSetupTest.SMTP.dynamicPort())
    private val storage = MemoryStorage()

    @BeforeTest fun start() {
        smtp.start()
        storage.settings("mail").apply {
            set("host", "127.0.0.1"); set("port", smtp.smtp.port.toString()); set("security", "none")
            set("from", "Site <noreply@example.org>"); set("inbox", "http://localhost:8025")
        }
    }

    @AfterTest fun stop() = smtp.stop()

    private fun ApplicationTestBuilder.site() = application {
        installContentSite {
            this.storage = this@MailTest.storage
            sessionSecret = "test-secret"
            identity = IdentityProvider { if (it.id == "admin") setOf(Roles.ADMIN) else emptySet() }
            plugins += Mail()
        }
        routing { get("/as/{who}") { call.sessions.set(UserSession(call.parameters["who"]!!, "x", null, "t")); call.respondText("ok") } }
    }

    @Test
    fun `the site's mailer sends through the settings, and says so in the log`() = testApplication {
        site()
        startApplication()
        application.site.mailer!!.send("alice@example.org", "Bienvenue", "Bonjour Alice")
        val received = smtp.receivedMessages.single()
        assertEquals("Bienvenue", received.subject)
        assertEquals("alice@example.org", received.allRecipients.single().toString())

        val admin = createClient { install(HttpCookies) }.also { it.get("/as/admin") }
        assertContains(admin.get("/api/mail/log").bodyAsText(), """"Bienvenue","sent"""")
    }

    @Test
    fun `a failed send throws, and is logged with its reason`() = testApplication {
        site()
        startApplication()
        storage.settings("mail")["host"] = ""
        assertFailsWith<IllegalStateException> { application.site.mailer!!.send("a@b.c", "x", "y") }
        assertEquals("mail is not configured", storage.records("mail", "log").list().values.single().getString("error"))
    }

    @Test
    fun `the mailbox entry frames the configured webmail, for admins only`() = testApplication {
        site()
        val admin = createClient { install(HttpCookies); followRedirects = false }.also { it.get("/as/admin") }
        assertEquals("http://localhost:8025", admin.get("/mail/inbox").headers[HttpHeaders.Location])
        assertEquals(HttpStatusCode.Forbidden, client.get("/mail/inbox").status)
    }
}
