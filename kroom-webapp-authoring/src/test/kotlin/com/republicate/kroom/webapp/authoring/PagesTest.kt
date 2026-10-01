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
        val created = editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/company/history","title":{"en":"Our history"},"layout":"article"}""")
        assertEquals(HttpStatusCode.OK, created.status, created.bodyAsText())
        assertContains(created.bodyAsText(), """"status":"draft"""")

        assertEquals(HttpStatusCode.NotFound, visitor().get("/company/history").status, "a draft is nobody's but its editors'")
        val draft = editor.get("/company/history").bodyAsText()
        assertContains(draft, "<title>Our history — kroom</title>")
        assertContains(draft, """data-layout="article"""")
        assertContains(draft, """data-content="pages/company/history/content.md"""", message = "the block where a template's would be, with its handle")
        assertFalse(visitor().get("/about").bodyAsText().contains("history"), "a visitor's menu does not know it")
        assertContains(editor.get("/about").bodyAsText(), """<a href="/company/history" class="draft">History</a><small class="draft">draft</small>""")

        storage.content.write("pages/company/history/content.md", "## Since 1998\n\nA long **story**.", mapOf("author" to "ed"))
        editor.json(HttpMethod.Put, "/api/site/pages/company/history", """{"status":"published"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        val page = visitor().get("/company/history").bodyAsText()
        assertContains(page, "<h2>Since 1998</h2>")
        assertContains(page, "A long <strong>story</strong>")
        assertContains(page, """<meta property="og:title" content="Our history">""")
        assertContains(visitor().get("/about").bodyAsText(), """<a href="/company/history">History</a>""", message = "published, it is in the menu, under its section")
        assertContains(visitor().get("/about").bodyAsText(), """<li class="section"><a href="/company/history">Company</a>""", message = "a section nobody wrote leads to its first page")
        assertContains(visitor("admin").get("/api/site/pages").bodyAsText(), """"path":"/company/history","layout":"article"""")

        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/company/history"}""").let { assertEquals(HttpStatusCode.Conflict, it.status) }
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/about"}""").let { assertEquals(HttpStatusCode.Conflict, it.status, "a template's page is not for the taking") }
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"bad path"}""").let { assertEquals(HttpStatusCode.BadRequest, it.status) }
        assertEquals(HttpStatusCode.OK, editor.delete("/api/site/pages/company/history").status)
        assertEquals(HttpStatusCode.NotFound, visitor().get("/company/history").status)
        assertEquals("## Since 1998\n\nA long **story**.", storage.content.read("pages/company/history/content.md")!!.body, "its blocks stay")
    }

    @Test
    fun `a region is what its block makes it - a sliver for who may write it, nothing for a visitor, itself once written`() = testApplication {
        site()
        val editor = visitor("ed")
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/legal/notes","title":{"en":"Notes"},"status":"published"}""")
        val asEditor = editor.get("/legal/notes").bodyAsText()
        assertContains(asEditor, """<aside class="east"><div class="kroom-region-empty">""", message = "an unwritten region: a sliver")
        assertContains(asEditor, """data-content="pages/legal/notes/east.md"""", message = "with its handle")
        assertContains(asEditor, "<small>right sidebar</small>")
        assertContains(asEditor, """<section class="container top"><div class="kroom-region-empty">""")
        val asVisitor = visitor().get("/legal/notes").bodyAsText()
        assertFalse(asVisitor.contains("""class="east""""), "a visitor sees no empty region")
        assertFalse(asVisitor.contains("kroom-region"), "nor any sliver")
        assertContains(visitor("au").get("/legal/notes").bodyAsText(), "kroom-region-empty", message = "an author may write it too")

        // the west has a site default: the editor sees it with the handle, the visitor sees it plain
        assertContains(asEditor, """data-content="pages/legal/notes/west.md"""")
        assertContains(asEditor, """<nav class="west" aria-label="Legal">""")
        assertContains(asVisitor, """<nav class="west" aria-label="Legal">""")
        assertFalse(asVisitor.contains("pages/legal/notes/west.md"))

        storage.content.write("pages/legal/notes/east.md", "On the **side**", mapOf("author" to "ed"))
        storage.content.write("pages/legal/notes/west.md", "Our own **west**", mapOf("author" to "ed"))
        val written = visitor().get("/legal/notes").bodyAsText()
        assertContains(written, "On the <strong>side</strong>")
        assertContains(written, "Our own <strong>west</strong>")
        assertFalse(written.contains("""<nav class="west""""), "the override replaces the default")
        assertEquals(HttpStatusCode.OK, editor.delete("/api/content/pages/legal/notes/west.md").status)
        assertContains(visitor().get("/legal/notes").bodyAsText(), """<nav class="west" aria-label="Legal">""", message = "trashed, the default is back")
    }

    @Test
    fun `a block of an authored page goes through the editor like any other - lock, preview within its page, submit`() = testApplication {
        site()
        val editor = visitor("ed")
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/company","title":{"en":"Company"}}""")
        val path = "pages/company/content.md"
        val held = editor.post("/api/content/lock/$path")
        assertEquals(HttpStatusCode.OK, held.status, held.bodyAsText())
        val preview = editor.json(HttpMethod.Post, "/api/content/preview/$path", """{"page":"/company","body":"## Draft\n\nbeing *written*"}""")
        assertEquals(HttpStatusCode.OK, preview.status, preview.bodyAsText())
        assertContains(preview.bodyAsText(), "being <em>written</em>")
        assertContains(preview.bodyAsText(), "<title>Company — kroom</title>", message = "the page, not a fragment")
        val submitted = editor.json(HttpMethod.Post, "/api/content/$path", """{"page":"/company","rev":"","body":"## Written"}""")
        assertEquals(HttpStatusCode.OK, submitted.status, submitted.bodyAsText())
        assertEquals("## Written", storage.content.read(path)!!.body)
    }
}
