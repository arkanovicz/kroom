package com.republicate.kroom.plugins.forms

import com.republicate.kroom.webapp.authoring.IdentityProvider
import com.republicate.kroom.webapp.authoring.MemoryStorage
import com.republicate.kroom.webapp.authoring.Roles
import com.republicate.kroom.webapp.authoring.installContentSite
import com.republicate.kroom.webapp.authoring.site
import com.republicate.kroom.webapp.core.Mailer
import com.republicate.kroom.webapp.session.UserSession
import com.republicate.kson.Json
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class FormsTest {

    private val forms = Forms(mapOf("send" to "Envoyer"))
    private val storage = MemoryStorage().apply {
        content.write("pages/contact.md", "Écrivez-nous :\n\n\$forms.contact(\"adhésion\")", emptyMap())
    }

    private fun ApplicationTestBuilder.site() = application {
        installContentSite {
            this.storage = this@FormsTest.storage
            sessionSecret = "test-secret"
            identity = IdentityProvider { if (it.id == "editor") setOf(Roles.EDITOR) else emptySet() }
            plugins += forms
        }
        routing { get("/as/{who}") { call.sessions.set(UserSession(call.parameters["who"]!!, "x", null, "t")); call.respondText("ok") } }
    }

    private val messages get() = storage.records("forms", "messages")

    @Test
    fun `a block calls the form, a visitor posts it, an editor reads it`() = testApplication {
        site()
        val page = client.get("/index").bodyAsText()
        assertContains(page, """<form class="kroom-form" method="post" action="/forms/contact"""")
        assertContains(page, """name="topic" value="adhésion"""")
        assertContains(page, "Envoyer")

        val visitor = createClient { followRedirects = false }
        val sent = visitor.submitForm("/forms/contact", parameters {
            append("topic", "adhésion"); append("name", "Alice"); append("email", "alice@example.org"); append("message", "Bonjour")
        }) { header(HttpHeaders.Referrer, "http://localhost/index") }
        assertEquals(HttpStatusCode.Found, sent.status)
        assertEquals("/index", sent.headers[HttpHeaders.Location])
        assertEquals("Alice", messages.list().values.single().getString("name"))

        val editor = createClient { install(HttpCookies) }.also { it.get("/as/editor") }
        assertContains(editor.get("/api/forms/messages").bodyAsText(), "Bonjour")
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/forms/messages").status)
    }

    @Test
    fun `a robot filling the trap is thanked and forgotten, an incomplete form refused`() = testApplication {
        site()
        client.submitForm("/forms/contact", parameters {
            append("name", "bot"); append("email", "bot@spam"); append("message", "buy"); append("website", "http://spam")
        })
        assertEquals(0, messages.list().size)
        assertEquals(HttpStatusCode.BadRequest, client.submitForm("/forms/contact", parameters { append("name", "Alice") }).status)
    }

    @Test
    fun `each message is mailed to notify, when the site has a mailer`() = testApplication {
        site()
        startApplication()
        val sent = kotlinx.coroutines.CompletableDeferred<Triple<String, String, String>>()
        application.site.mailer = Mailer { to, subject, body -> sent.complete(Triple(to, subject, body)) }
        storage.settings("forms")["notify"] = "bureau@example.org"
        client.submitForm("/forms/contact", parameters {
            append("topic", "adhésion"); append("name", "Alice"); append("email", "alice@example.org"); append("message", "Bonjour")
        })
        val (to, subject, body) = kotlinx.coroutines.withTimeout(5000) { sent.await() }
        assertEquals("bureau@example.org", to)
        assertEquals("[adhésion] Alice", subject)
        assertContains(body, "Bonjour")
    }

    @Test
    fun `messages older than the retention are purged`() = testApplication {
        site()
        startApplication()
        val day = 86_400_000L
        messages.add(Json.MutableObject().apply { set("time", 0L) })
        messages.add(Json.MutableObject().apply { set("time", 400 * day) })
        storage.settings("forms")["retentionDays"] = "30"
        val site = application.site
        assertEquals(1, forms.purge(site, 420 * day))
        assertEquals(1, messages.list().size)
    }
}
