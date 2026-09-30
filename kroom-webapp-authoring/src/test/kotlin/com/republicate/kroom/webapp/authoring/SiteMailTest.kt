package com.republicate.kroom.webapp.authoring

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import com.republicate.kroom.webapp.core.Mailer
import io.ktor.server.testing.*
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The site mails over its own settings — or over the application's transport, or not at all. */
class SiteMailTest {

    private val smtp = GreenMail(ServerSetupTest.SMTP.dynamicPort())
    private val storage = MemoryStorage()

    @BeforeTest fun start() = smtp.start()
    @AfterTest fun stop() = smtp.stop()

    private fun ApplicationTestBuilder.site() = application {
        installContentSite { this.storage = this@SiteMailTest.storage; sessionSecret = "test-secret" }
    }

    @Test
    fun `no host, no mailer — a host, and the next mail goes through it`() = testApplication {
        site()
        startApplication()
        assertNull(application.site.mailer)
        storage.settings("site").apply {
            set("smtpHost", "127.0.0.1"); set("smtpPort", smtp.smtp.port.toString()); set("smtpSecurity", "none")
            set("mailFrom", "Site <noreply@example.org>")
        }
        application.site.mailer!!.send("alice@example.org", "Bienvenue", "Bonjour Alice")
        val received = smtp.receivedMessages.single()
        assertEquals("Bienvenue", received.subject)
        assertEquals("alice@example.org", received.allRecipients.single().toString())
        assertEquals("Site <noreply@example.org>", received.from.single().toString())
    }

    @Test
    fun `a host that vanished under a sender throws`() = testApplication {
        site()
        startApplication()
        storage.settings("site")["smtpHost"] = "127.0.0.1"
        val mailer = application.site.mailer!!
        storage.settings("site")["smtpHost"] = ""
        assertFailsWith<IllegalStateException> { mailer.send("a@b.c", "x", "y") }
    }

    @Test
    fun `the application's own transport wins over the settings`() = testApplication {
        site()
        startApplication()
        var sent: String? = null
        application.site.mailer = Mailer { to, _, _ -> sent = to }
        application.site.mailer!!.send("bob@example.org", "x", "y")
        assertEquals("bob@example.org", sent)
    }
}
