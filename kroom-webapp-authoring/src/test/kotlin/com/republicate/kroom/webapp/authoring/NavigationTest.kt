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
import kotlin.test.assertTrue

/** The menu: derived from the pages as a tree, or stored by an admin, in the request's language. */
class NavigationTest {

    private val storage = MemoryStorage()

    private fun ApplicationTestBuilder.site(lang: ((io.ktor.server.application.ApplicationCall) -> String?)? = null) = application {
        installContentSite {
            storage = this@NavigationTest.storage
            sessionSecret = "test-secret"
            identity = IdentityProvider { mapOf("admin" to setOf(Roles.ADMIN))[it.id].orEmpty() }
            loginPage = "pages/login.html"
            requestLanguage = lang
        }
        routing { get("/as/{who}") { call.sessions.set(UserSession(call.parameters["who"]!!, "x", null, "t")); call.respondText("ok") } }
    }

    private suspend fun ApplicationTestBuilder.admin(): HttpClient = createClient { install(HttpCookies) }.also { it.get("/as/admin") }

    @Test
    fun `derived from the pages, a deeper URL nests under its segment, a section nobody wrote is words`() = testApplication {
        site()
        val page = client.get("/legal/terms").bodyAsText()
        // the header: the tree from its root, the section marked as the trail, the page as current
        assertContains(page, """<li class="section"><span aria-current="true">Legal</span><ul><li><a href="/legal/terms" aria-current="page">Terms</a></li></ul></li>""")
        assertContains(page, """<a href="/about">About</a>""")
        assertFalse(page.contains(">Login</a>"), "the login page is hidden from the menu")
        // the west region: the section's pages
        assertContains(page, """<nav class="west" aria-label="Legal">""")
        assertFalse(client.get("/about").bodyAsText().contains("""class="west""""), "no section, no west")
    }

    @Test
    fun `a stored menu speaks the request's language, the default filling the gaps`() = testApplication {
        site(lang = { it.request.queryParameters["lang"] })
        storage.settings("site")["languages"] = "fr"
        storage.records("site", "menu").put("tree", com.republicate.kson.Json.parse("""{"items":[
            {"slug":"about","label":{"en":"The Company","fr":"La Société"},"description":{"en":"Who we are"}},
            {"slug":"legal","label":{"en":"Legal"},"children":[{"slug":"terms","label":{"en":"Terms","fr":"Mentions"}}]},
            {"href":"https://example.org","label":{"en":"Elsewhere"}}
        ]}""") as com.republicate.kson.Json.Object)
        val en = client.get("/about").bodyAsText()
        assertContains(en, """<a href="/about" aria-current="page">The Company</a><small>Who we are</small>""")
        assertContains(en, """<a href="https://example.org" rel="external">Elsewhere</a>""")
        val fr = client.get("/about?lang=fr").bodyAsText()
        assertContains(fr, """<html lang="fr"""")
        assertContains(fr, """>La Société</a><small>Who we are</small>""")
        assertContains(fr, """<span>Legal</span>""", message = "a section without a page, in the default language")
        assertContains(fr, """>Mentions</a>""")
        assertContains(client.get("/about?lang=de").bodyAsText(), """<html lang="en"""", message = "a language the site does not speak is the default")
    }

    @Test
    fun `the admin reads the tree with each entry resolved or not, stores one, and goes back to the pages`() = testApplication {
        site()
        val admin = admin()
        val derived = admin.get("/api/site/menu").bodyAsText()
        assertContains(derived, """"stored":false""")
        assertContains(derived, """"slug":"legal","label":{"en":"Legal"},"children":[{"slug":"terms","label":{"en":"Terms"},"path":"/legal/terms","resolved":true}],"path":"/legal","resolved":false""")

        val stored = admin.put("/api/site/menu") {
            contentType(ContentType.Application.Json)
            setBody("""{"items":[{"slug":"about","label":{"en":"About us"}},{"slug":"nowhere","label":{"en":"Soon"}}]}""")
        }
        assertEquals(HttpStatusCode.OK, stored.status, stored.bodyAsText())
        val read = admin.get("/api/site/menu").bodyAsText()
        assertContains(read, """"stored":true""")
        assertContains(read, """"slug":"nowhere","label":{"en":"Soon"},"path":"/nowhere","resolved":false""")
        assertContains(client.get("/about").bodyAsText(), """<a href="/about" aria-current="page">About us</a>""")
        assertContains(client.get("/about").bodyAsText(), "<span>Soon</span>")

        admin.put("/api/site/menu") { contentType(ContentType.Application.Json); setBody("""{"items":[{"slug":"a b","label":{}}]}""") }
            .let { assertEquals(HttpStatusCode.BadRequest, it.status) }
        admin.put("/api/site/menu") { contentType(ContentType.Application.Json); setBody("""{"items":[{"slug":"x","href":"https://y","label":{}}]}""") }
            .let { assertEquals(HttpStatusCode.BadRequest, it.status) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/site/menu").status)

        assertEquals(HttpStatusCode.OK, admin.delete("/api/site/menu").status)
        assertTrue(storage.records("site", "menu").get("tree") == null)
        assertContains(client.get("/about").bodyAsText(), """<a href="/about" aria-current="page">About</a>""")
    }
}
