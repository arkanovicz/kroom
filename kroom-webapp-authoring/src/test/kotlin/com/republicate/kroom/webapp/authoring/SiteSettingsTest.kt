package com.republicate.kroom.webapp.authoring

import com.republicate.kroom.webapp.session.UserSession
import io.ktor.client.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The site's own configuration: declared like a plugin's, kroom's then the application's, read by templates. */
class SiteSettingsTest {

    private val storage = MemoryStorage()

    private fun ApplicationTestBuilder.site() = application {
        installContentSite {
            storage = this@SiteSettingsTest.storage
            sessionSecret = "test-secret"
            identity = IdentityProvider { mapOf("admin" to setOf(Roles.ADMIN))[it.id].orEmpty() }
            settings += Setting.text("motto", "Motto", default = "festina lente")
        }
        routing {
            get("/as/{who}") { call.sessions.set(UserSession(call.parameters["who"]!!, "x", null, "test")); call.respondText("ok") }
        }
    }

    private suspend fun ApplicationTestBuilder.admin(): HttpClient =
        createClient { install(HttpCookies); followRedirects = false }.also { it.get("/as/admin") }

    @Test
    fun `kroom's settings then the application's, defaults showing through, a secret never read back`() = testApplication {
        site()
        val listed = admin().get("/api/site/settings").bodyAsText()
        assertContains(listed, """"key":"name"""")
        assertContains(listed, """"value":"kroom"""")
        assertContains(listed, """"key":"motto"""")
        assertContains(listed, """"value":"festina lente"""")
        assertContains(listed, """"key":"smtpPassword","label":"Password","type":"secret","group":"Mail","set":false""")
        assertFalse(listed.contains("plugins.disabled"), "undeclared keys stay unlisted")
    }

    @Test
    fun `an admin writes them, a visitor may not, and the page reads them`() = testApplication {
        site()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/site/settings").status)
        val admin = admin()
        admin.put("/api/site/settings") { contentType(ContentType.Application.Json); setBody("""{"name":"Les Vagabonds","smtpPassword":"","motto":null}""") }
            .let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        assertEquals("Les Vagabonds", storage.settings("site")["name"])
        assertEquals(null, storage.settings("site")["smtpPassword"], "an empty secret keeps what was there")
        val page = client.get("/about").bodyAsText()
        assertContains(page, "<title>About — Les Vagabonds</title>")
        assertContains(page, """<html lang="en"""")
        admin.put("/api/site/settings") { contentType(ContentType.Application.Json); setBody("""{"nope":"x"}""") }
            .let { assertEquals(HttpStatusCode.BadRequest, it.status) }
    }
}
