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
    fun `derived from the pages, a deeper URL nests under its segment, a section nobody wrote leads to its first page`() = testApplication {
        site()
        val page = client.get("/legal/terms").bodyAsText()
        // the header: the tree from its root, the section marked as the trail, the page as current
        assertContains(page, """<li class="section"><a href="/legal/terms" aria-current="true">Legal</a><ul><li><a href="/legal/terms" aria-current="page">Terms</a></li></ul></li>""")
        assertContains(page, """<a href="/about">About</a>""")
        assertFalse(page.contains(">Login</a>"), "the login page is hidden from the menu")
        // the west region: the section's pages
        assertContains(page, """<nav class="west" aria-label="Legal">""")
        assertFalse(client.get("/about").bodyAsText().contains("""class="west""""), "no section, no west")
        // the breadcrumb: the site, the section (leading to its first page), the page — inside a section only
        assertContains(page, """<nav aria-label="breadcrumb" class="container breadcrumb">""")
        assertContains(page, """<li><a href="/">kroom</a></li><li><a href="/legal/terms">Legal</a></li><li><span aria-current="page">Terms</span></li>""")
        assertFalse(client.get("/about").bodyAsText().contains("breadcrumb"), "a top-level page has none")
    }

    @Test
    fun `a stored menu speaks the request's language, the default filling the gaps`() = testApplication {
        site(lang = { it.request.queryParameters["lang"] })
        storage.settings("site")["languages"] = "fr"
        storage.records("site", "menu").put("tree", com.republicate.kson.Json.parse("""{"items":[
            {"slug":"about","label":{"en":"The Company","fr":"La Société"},"description":{"en":"Who we are"}},
            {"slug":"legal","label":{"en":"Legal"},"children":[{"slug":"terms","label":{"en":"Terms","fr":"Mentions"}}]}
        ]}""") as com.republicate.kson.Json.Object)
        val en = client.get("/about").bodyAsText()
        assertContains(en, """<a href="/about" aria-current="page">The Company</a><small>Who we are</small>""")
        val fr = client.get("/about?lang=fr").bodyAsText()
        assertContains(fr, """<html lang="fr"""")
        assertContains(fr, """>La Société</a><small>Who we are</small>""")
        assertContains(fr, """<a href="/legal/terms">Legal</a>""", message = "a section without a page, in the default language, leading to its first one")
        assertContains(fr, """>Mentions</a>""")
        assertContains(client.get("/about?lang=de").bodyAsText(), """<html lang="en"""", message = "a language the site does not speak is the default")
    }

    @Test
    fun `the stored tree orders and words what exists - it makes no page, and a page made since comes after`() = testApplication {
        site()
        val admin = admin()
        val derived = admin.get("/api/site/menu").bodyAsText()
        assertContains(derived, """"stored":false""")
        assertContains(derived, """"path":"/legal","kind":"section"""")
        assertContains(derived, """"path":"/legal/terms","kind":"template"""")
        val order = { body: String -> Regex(""""path":"(/[a-z]+)","kind"""").findAll(body).map { it.groupValues[1] }.toList() }
        assertEquals(listOf("/about", "/legal", "/story", "/welcome"), order(derived))

        val stored = admin.put("/api/site/menu") {
            contentType(ContentType.Application.Json)
            setBody("""{"items":[{"slug":"story","label":{"en":"Our story"}},{"slug":"nowhere","label":{"en":"Soon"}},{"slug":"about","label":{}}]}""")
        }
        assertEquals(HttpStatusCode.OK, stored.status, stored.bodyAsText())
        val read = admin.get("/api/site/menu").bodyAsText()
        assertContains(read, """"stored":true""")
        assertEquals(listOf("/story", "/about", "/legal", "/welcome"), order(read), "the arranged ones first, in their order, then the others")
        assertFalse(read.contains("nowhere"), "an entry no page answers is no page")
        val page = client.get("/about").bodyAsText()
        assertContains(page, """<a href="/story">Our story</a>""")
        assertContains(page, """<a href="/about" aria-current="page">About</a>""", message = "an entry without words keeps the page's own")
        assertFalse(page.contains("Soon"))

        admin.put("/api/site/menu") { contentType(ContentType.Application.Json); setBody("""{"items":[{"slug":"a b","label":{}}]}""") }
            .let { assertEquals(HttpStatusCode.BadRequest, it.status) }
        admin.put("/api/site/menu") { contentType(ContentType.Application.Json); setBody("""{"items":[{"href":"https://y","label":{}}]}""") }
            .let { assertEquals(HttpStatusCode.BadRequest, it.status, "an entry is a page: no address elsewhere") }
        admin.put("/api/site/menu") { contentType(ContentType.Application.Json); setBody("""{"items":[{"slug":"a","label":{}},{"slug":"a","label":{}}]}""") }
            .let { assertEquals(HttpStatusCode.BadRequest, it.status, "two entries cannot be one page") }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/site/menu").status)

        assertEquals(HttpStatusCode.OK, admin.delete("/api/site/menu").status)
        assertTrue(storage.records("site", "menu").get("tree") == null)
        assertEquals(listOf("/about", "/legal", "/story", "/welcome"), order(admin.get("/api/site/menu").bodyAsText()))
    }

    @Test
    fun `the header's menu goes as deep as the site says, the west lists the section whatever the depth`() = testApplication {
        site()
        val two = client.get("/legal/terms").bodyAsText()
        val header = { page: String -> page.substringAfter("<header").substringBefore("</header>") }
        assertContains(header(two), """<a href="/legal/terms" aria-current="page">Terms</a>""", message = "two levels by default")
        storage.settings("site")["menuDepth"] = "1"
        val one = client.get("/legal/terms").bodyAsText()
        assertFalse(header(one).contains("Terms"), "one level: the sections only")
        assertContains(header(one), """<a href="/legal/terms" aria-current="true">Legal</a>""")
        assertContains(one.substringAfter("""<nav class="west""""), """<a href="/legal/terms" aria-current="page">Terms</a>""")
    }
}
