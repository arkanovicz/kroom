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

/** Pages editors make: a record and blocks, drafts theirs to see, published for all, rendered through one template. */
class PagesTest {

    private val storage = MemoryStorage()

    private fun ApplicationTestBuilder.site() = application {
        installContentSite {
            storage = this@PagesTest.storage
            sessionSecret = "test-secret"
            identity = IdentityProvider { mapOf("admin" to setOf(Roles.ADMIN), "ed" to setOf(Roles.EDITOR), "au" to setOf(Roles.AUTHOR))[it.id].orEmpty() }
            loginPage = "pages/login.html"
        }
        routing { get("/as/{who}") { call.sessions.set(UserSession(call.parameters["who"]!!, "x", null, "t")); call.respondText("ok") } }
    }

    private suspend fun ApplicationTestBuilder.visitor(name: String? = null): HttpClient =
        createClient { install(HttpCookies); followRedirects = false }.also { if (name != null) it.get("/as/$name") }

    private suspend fun HttpClient.json(method: HttpMethod, url: String, body: String) = request(url) {
        this.method = method; contentType(ContentType.Application.Json); setBody(body)
    }

    @Test
    fun `an editor creates a draft only its editors see, publishes it for all, and the blocks sit beside it`() = testApplication {
        site()
        val editor = visitor("ed")
        assertEquals(HttpStatusCode.Forbidden, visitor("au").json(HttpMethod.Post, "/api/site/pages", """{"path":"/company"}""").status, "an author writes blocks, not pages")
        val created = editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/company/history","title":{"en":"Our history"},"layout":"article","regions":["content","east"]}""")
        assertEquals(HttpStatusCode.OK, created.status, created.bodyAsText())
        assertContains(created.bodyAsText(), """"status":"draft"""")

        assertEquals(HttpStatusCode.NotFound, visitor().get("/company/history").status, "a draft is nobody's but its editors'")
        val draft = editor.get("/company/history").bodyAsText()
        assertContains(draft, "<title>Our history — kroom</title>")
        assertContains(draft, """data-layout="article"""")
        assertContains(draft, """data-content="pages/company/history/content.md"""", message = "the block where a template's would be, with its handle")
        assertContains(draft, """data-content="pages/company/history/east.md"""")
        assertFalse(visitor().get("/about").bodyAsText().contains("history"), "a visitor's menu does not know it")
        assertContains(editor.get("/about").bodyAsText(), """<a href="/company/history" class="draft">History</a><small class="draft">draft</small>""")

        storage.content.write("pages/company/history/content.md", "## Since 1998\n\nA long **story**.", mapOf("author" to "ed"))
        editor.json(HttpMethod.Put, "/api/site/pages/company/history", """{"status":"published"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        val page = visitor().get("/company/history").bodyAsText()
        assertContains(page, "<h2>Since 1998</h2>")
        assertContains(page, "A long <strong>story</strong>")
        assertContains(page, """<meta property="og:title" content="Our history">""")
        assertContains(visitor().get("/about").bodyAsText(), """<a href="/company/history">History</a>""", message = "published, it is in the menu, under its section")
        assertContains(visitor("admin").get("/api/site/pages").bodyAsText(), """"path":"/company/history","layout":"article"""")

        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/company/history"}""").let { assertEquals(HttpStatusCode.Conflict, it.status) }
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/about"}""").let { assertEquals(HttpStatusCode.Conflict, it.status, "a template's page is not for the taking") }
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"bad path"}""").let { assertEquals(HttpStatusCode.BadRequest, it.status) }
        assertEquals(HttpStatusCode.OK, editor.delete("/api/site/pages/company/history").status)
        assertEquals(HttpStatusCode.NotFound, visitor().get("/company/history").status)
        assertEquals("## Since 1998\n\nA long **story**.", storage.content.read("pages/company/history/content.md")!!.body, "its blocks stay")
    }

    @Test
    fun `a region with a site default shows it until the editor writes it, and again once trashed`() = testApplication {
        site()
        val editor = visitor("ed")
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/legal/notes","title":{"en":"Notes"},"regions":["content","west"],"status":"published"}""")
        val before = editor.get("/legal/notes").bodyAsText()
        assertContains(before, """data-content="pages/legal/notes/west.md"""", message = "the handle on the default")
        assertContains(before, """<nav class="west" aria-label="Legal">""", message = "the default: the section's pages")
        storage.content.write("pages/legal/notes/west.md", "Our own **west**", mapOf("author" to "ed"))
        val written = editor.get("/legal/notes").bodyAsText()
        assertContains(written, "Our own <strong>west</strong>")
        assertFalse(written.contains("""<nav class="west""""))
        assertEquals(HttpStatusCode.OK, editor.delete("/api/content/pages/legal/notes/west.md").status)
        assertContains(editor.get("/legal/notes").bodyAsText(), """<nav class="west" aria-label="Legal">""")
    }
}
