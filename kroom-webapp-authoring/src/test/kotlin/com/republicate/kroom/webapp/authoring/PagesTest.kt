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
import kotlin.test.assertNull

/** Pages editors make: a record and blocks, drafts theirs to see, published for all, rendered through one template. */
class PagesTest {

    private val storage = MemoryStorage()

    private fun ApplicationTestBuilder.site(pageTemplate: String? = null, vararg plugins: Plugin) = application {
        installContentSite {
            storage = this@PagesTest.storage
            sessionSecret = "test-secret"
            pageTemplate?.let { this.pageTemplate = it }
            this.plugins += plugins
            identity = IdentityProvider { mapOf("admin" to setOf(Roles.ADMIN), "ed" to setOf(Roles.EDITOR), "ed2" to setOf(Roles.EDITOR), "au" to setOf(Roles.AUTHOR))[it.id].orEmpty() }
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
        val created = editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/company/history","label":{"en":"Our history"},"layout":"article"}""")
        assertEquals(HttpStatusCode.OK, created.status, created.bodyAsText())
        assertContains(created.bodyAsText(), """"status":"draft"""")

        assertEquals(HttpStatusCode.NotFound, visitor().get("/company/history").status, "a draft is nobody's but its editors'")
        val draft = editor.get("/company/history").bodyAsText()
        assertContains(draft, "<title>Our history — kroom</title>")
        assertContains(draft, """data-layout="article"""")
        assertContains(draft, """data-content="pages/company/history/content.md"""", message = "the block where a template's would be, with its handle")
        assertFalse(visitor().get("/about").bodyAsText().contains("history"), "a visitor's menu does not know it")
        assertContains(editor.get("/about").bodyAsText(), """<a href="/company/history" class="draft">Our history</a><small class="draft">draft</small>""")

        storage.content.write("pages/company/history/content.md", "## Since 1998\n\nA long **story**.", mapOf("author" to "ed"))
        editor.json(HttpMethod.Put, "/api/site/pages/company/history", """{"status":"published"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        val page = visitor().get("/company/history").bodyAsText()
        assertContains(page, "<h2>Since 1998</h2>")
        assertContains(page, "A long <strong>story</strong>")
        assertContains(page, """<meta property="og:title" content="Our history">""")
        assertContains(visitor().get("/about").bodyAsText(), """<a href="/company/history">Our history</a>""", message = "published, it is in the menu under its section, by the name it was given: its label is its title")
        assertContains(visitor().get("/about").bodyAsText(), """<li class="section"><a href="/company/history">Company</a>""", message = "a section nobody wrote leads to its first page")
        assertContains(visitor("admin").get("/api/site/menu").bodyAsText(), """"path":"/company/history","kind":"authored","status":"published","layout":"article","movable":true""")
        editor.json(HttpMethod.Put, "/api/site/pages/company/history", """{"layout":"sidebar"}""").let { assertEquals(HttpStatusCode.OK, it.status) }
        assertContains(visitor().get("/company/history").bodyAsText(), """data-layout="sidebar"""")

        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/company/history"}""").let { assertEquals(HttpStatusCode.Conflict, it.status) }
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/about"}""").let { assertEquals(HttpStatusCode.Conflict, it.status, "a template's page is not for the taking") }
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/club/anything"}""").let { assertEquals(HttpStatusCode.Conflict, it.status, "nor a place a placeholder template would answer") }
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"bad path"}""").let { assertEquals(HttpStatusCode.BadRequest, it.status) }
        assertEquals(HttpStatusCode.OK, editor.delete("/api/site/pages/company/history").status)
        assertEquals(HttpStatusCode.NotFound, visitor().get("/company/history").status)
        assertEquals("## Since 1998\n\nA long **story**.", storage.content.read("pages/company/history/content.md")!!.body, "its blocks stay")
    }

    @Test
    fun `a region is what its block makes it - a sliver for who may write it, nothing for a visitor, itself once written`() = testApplication {
        site()
        val editor = visitor("ed")
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/legal/notes","label":{"en":"Notes"},"status":"published"}""")
        val asEditor = editor.get("/legal/notes").bodyAsText()
        assertContains(asEditor, """<aside class="east"><div class="kroom-region-empty">""", message = "an unwritten region: a sliver")
        assertContains(asEditor, """data-content="pages/legal/notes/east.md"""", message = "with its handle")
        assertContains(asEditor, "<small>right sidebar</small>")
        assertContains(asEditor, """<section class="container top"><div class="kroom-region-empty">""")
        val asVisitor = visitor().get("/legal/notes").bodyAsText()
        assertFalse(asVisitor.contains("""class="east""""), "a visitor sees no empty region")
        assertFalse(asVisitor.contains("kroom-region"), "nor any sliver")
        assertContains(visitor("au").get("/legal/notes").bodyAsText(), "kroom-region-empty", message = "an author may write it too")

        // the west is the site's, not the page's: the same for editor and visitor, no handle
        assertContains(asEditor, """<nav class="west" aria-label="Legal">""")
        assertContains(asVisitor, """<nav class="west" aria-label="Legal">""")
        assertFalse(asEditor.contains("pages/legal/notes/west.md"), "no block for a page's west")

        storage.content.write("pages/legal/notes/east.md", "On the **side**", mapOf("author" to "ed"))
        storage.content.write("pages/legal/notes/west.md", "Our own **west**", mapOf("author" to "ed"))
        val written = visitor().get("/legal/notes").bodyAsText()
        assertContains(written, "On the <strong>side</strong>")
        assertFalse(written.contains("Our own"), "a west block under the page is ignored")
        assertContains(written, """<nav class="west" aria-label="Legal">""", message = "the site's west stays")
    }

    @Test
    fun `a root page is born with its table of contents - the loop over its children, a block like any other`() = testApplication {
        site()
        val editor = visitor("ed")
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/guides","label":{"en":"Guides"},"status":"published"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        assertEquals(AuthoredPage.TOC, storage.content.read("pages/guides/content.md")?.body, "the loop, stored as the page's content")
        assertEquals("ed", storage.content.read("pages/guides/content.md")?.meta?.get("author"))
        assertFalse(visitor().get("/guides").bodyAsText().contains("""<li><a href="/guides/"""), "nothing under it yet: the loop lists nothing")

        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/guides/first","label":{"en":"First steps"},"status":"published"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        assertNull(storage.content.read("pages/guides/first/content.md"), "a deeper page is born empty")
        editor.json(HttpMethod.Put, "/api/site/menu", """{"items":[{"slug":"guides","label":{"en":"Guides"},"children":[{"slug":"first","label":{"en":"First steps"},"description":{"en":"Where to begin"}}]}]}""")
            .let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        val toc = visitor().get("/guides").bodyAsText()
        assertContains(toc, """<li><a href="/guides/first">First steps</a> — Where to begin</li>""", message = "the child, its words from the menu")

        // the loop is the editor's: rewritten, the page says what it says; made again where blocks were left, a page keeps them
        storage.content.write("pages/guides/content.md", "All our **guides**", mapOf("author" to "ed"))
        assertContains(visitor().get("/guides").bodyAsText(), "All our <strong>guides</strong>")
        assertEquals(HttpStatusCode.OK, editor.delete("/api/site/pages/guides").status)
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/guides","status":"published"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        assertContains(visitor().get("/guides").bodyAsText(), "All our <strong>guides</strong>", message = "its block stayed, the loop is not written over it")
    }

    @Test
    fun `a block of an authored page goes through the editor like any other - lock, preview within its page, submit`() = testApplication {
        site()
        val editor = visitor("ed")
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/company","label":{"en":"Company"}}""")
        val path = "pages/company/content.md"
        val held = editor.post("/api/content/lock/$path")
        assertEquals(HttpStatusCode.OK, held.status, held.bodyAsText())
        // a root page is born with its loop: the editor opens on it, and submits over its revision
        assertContains(held.bodyAsText(), "%foreach(")
        val rev = Regex(""""rev":"([^"]*)"""").find(held.bodyAsText())!!.groupValues[1]
        val preview = editor.json(HttpMethod.Post, "/api/content/preview/$path", """{"page":"/company","body":"## Draft\n\nbeing *written*"}""")
        assertEquals(HttpStatusCode.OK, preview.status, preview.bodyAsText())
        assertContains(preview.bodyAsText(), "being <em>written</em>")
        assertContains(preview.bodyAsText(), "<title>Company — kroom</title>", message = "the page, not a fragment")
        val submitted = editor.json(HttpMethod.Post, "/api/content/$path", """{"page":"/company","rev":"$rev","body":"## Written"}""")
        assertEquals(HttpStatusCode.OK, submitted.status, submitted.bodyAsText())
        assertEquals("## Written", storage.content.read(path)!!.body)
    }

    @Test
    fun `an authored page moves with its blocks, their past and its words - unless something stands in the way`() = testApplication {
        site()
        val editor = visitor("ed")
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/notes","label":{"en":"Field notes"},"status":"published"}""")
        storage.content.write("pages/notes/content.md", "First.", mapOf("author" to "ed"))
        storage.content.write("pages/notes/content.md", "Second.", mapOf("author" to "ed"))

        assertEquals(HttpStatusCode.Forbidden, visitor("au").json(HttpMethod.Post, "/api/site/pages/move", """{"from":"/notes","to":"/legal/notes"}""").status)
        val moved = editor.json(HttpMethod.Post, "/api/site/pages/move", """{"from":"/notes","to":"/legal/notes"}""")
        assertEquals(HttpStatusCode.OK, moved.status, moved.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, visitor().get("/notes").status, "the old address answers nothing: no redirect is written")
        val page = visitor().get("/legal/notes").bodyAsText()
        assertContains(page, "Second.")
        assertContains(page, "<title>Field notes — kroom</title>", message = "its words followed")
        assertEquals(null, storage.content.read("pages/notes/content.md"))
        val history = (storage.content as Versioned).log("pages/legal/notes/content.md")
        assertEquals(listOf("moved from pages/notes/content.md", null, null, null), history.map { it.message }, "its past followed (the loop it was born with, two writes), and the move is in it")
        assertContains(visitor().get("/about").bodyAsText(), """<a href="/legal/notes">Field notes</a>""")

        // its words follow even under a parent the stored arrangement never heard of
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/fresh","status":"published"}""")
        storage.records("site", "menu").put("tree", com.republicate.kson.Json.parse("""{"items":[{"slug":"legal","label":{"en":"Legal"},"children":[{"slug":"notes","label":{"en":"Field notes"}}]}]}""") as com.republicate.kson.Json.Object)
        editor.json(HttpMethod.Post, "/api/site/pages/move", """{"from":"/legal/notes","to":"/fresh/notes"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        assertContains(visitor().get("/fresh/notes").bodyAsText(), "<title>Field notes — kroom</title>")
        editor.json(HttpMethod.Post, "/api/site/pages/move", """{"from":"/fresh/notes","to":"/legal/notes"}""")

        fun refused(from: String, to: String, why: String) = suspend {
            val answer = editor.json(HttpMethod.Post, "/api/site/pages/move", """{"from":"$from","to":"$to"}""")
            assertEquals(HttpStatusCode.Conflict, answer.status, why)
            assertContains(answer.bodyAsText(), why)
        }
        refused("/legal/notes", "/about", "a page already answers /about")()
        refused("/legal/notes", "/club/notes", "a page already answers /club/notes")()
        refused("/about", "/legal/about", "no authored page at /about")()
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/a"}""")
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/a/b"}""")
        refused("/a", "/legal/a", "/a has pages under it")()
        assertContains(visitor("admin").get("/api/site/menu").bodyAsText(), """"path":"/a","kind":"authored","status":"draft","layout":"default","movable":false""")
        visitor("ed2").post("/api/content/lock/pages/a/b/content.md").let { assertEquals(HttpStatusCode.OK, it.status) }
        storage.content.write("pages/a/b/content.md", "being written", mapOf("author" to "ed2"))
        refused("/a/b", "/b", "pages/a/b/content.md is being written by someone else")()
        assertEquals("being written", storage.content.read("pages/a/b/content.md")!!.body, "a refused move moves nothing")
    }

    @Test
    fun `on files - a miss that is no page path is a plain 404, and nested pages are stored, served and moved`() = testApplication {
        val root = java.nio.file.Files.createTempDirectory("kroom-pages")
        try {
            application {
                installContentSite {
                    storage = FileStorage(root)
                    sessionSecret = "test-secret"
                    identity = IdentityProvider { if (it.id == "ed") setOf(Roles.EDITOR) else emptySet() }
                }
                routing { get("/as/{who}") { call.sessions.set(UserSession(call.parameters["who"]!!, "x", null, "t")); call.respondText("ok") } }
            }
            for (miss in listOf("/favicon.ico", "/apple-touch-icon.png", "/.env", "/a_b/c.d")) {
                assertEquals(HttpStatusCode.NotFound, client.get(miss).status, miss)
            }
            val editor = visitor("ed")
            editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/company/history_1","label":{"en":"History"},"status":"published"}""")
                .let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
            editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/company_-history_1"}""")
                .let { assertEquals(HttpStatusCode.OK, it.status, "a segment spelled like the escape is another page") }
            assertContains(visitor().get("/company/history_1").bodyAsText(), "<title>History — kroom</title>")
            editor.json(HttpMethod.Post, "/api/site/pages/move", """{"from":"/company/history_1","to":"/history"}""")
                .let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
            assertEquals(HttpStatusCode.OK, visitor().get("/history").status)
            assertEquals(HttpStatusCode.NotFound, visitor().get("/company/history_1").status)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an application renders authored pages through a template of its own`() = testApplication {
        site(pageTemplate = "inc/custom_page.html")
        val editor = visitor("ed")
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/company/team","label":{"en":"Team"}}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        storage.content.write("pages/company/team/content-fr.md", "Bonjour l'équipe.", mapOf("author" to "ed"))
        val page = editor.get("/company/team").bodyAsText()
        assertContains(page, "<title>Team</title>")
        assertContains(page, """<main class="custom">""")
        assertContains(page, "Bonjour l'équipe.")
        assertContains(page, """data-content="pages/company/team/content-fr.md"""", message = "the block the application named, edited in place like any other")
    }

    @Test
    fun `a page born with a layout a plugin seeds starts as the plugin's call, a root page as its contents`() = testApplication {
        val seeder = object : Plugin {
            override val id = "seeder"
            override fun install(site: Site) { site.seed("gallery") { page -> "\$gallery.show(\"${page.path.trim('/')}\")" } }
        }
        site(null, seeder)
        val editor = visitor("ed")
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/photos","layout":"gallery"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        assertEquals("\$gallery.show(\"photos\")", storage.content.read("pages/photos/content.md")!!.body, "the layout's seed wins over a root page's contents")
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/photos/plage","layout":"gallery"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        assertEquals("\$gallery.show(\"photos/plage\")", storage.content.read("pages/photos/plage/content.md")!!.body)
        assertEquals("ed", storage.content.read("pages/photos/plage/content.md")!!.author, "the page's author signs the seed")
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/team"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        assertEquals(AuthoredPage.TOC, storage.content.read("pages/team/content.md")!!.body)
        assertNull(storage.content.read("pages/photos/plage/east.md"))
    }

    @Test
    fun `what is typed is data - a label, the site's name, a path segment are escaped where a page shows them, and a menu link runs nothing`() = testApplication {
        site()
        val editor = visitor("ed")
        storage.settings("site")["name"] = "<b>NAME</b>"
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/x","label":{"en":"<img src=x onerror=alert(1)>"}}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        storage.content.write("pages/x/content.md", "Some **words**.", mapOf("author" to "ed"))
        editor.json(HttpMethod.Put, "/api/site/pages/x", """{"status":"published"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }

        val page = visitor().get("/x").bodyAsText()
        assertContains(page, "<title>&lt;img src=x onerror=alert(1)> — &lt;b>NAME&lt;/b></title>", message = "a title is text up to its closing tag: no tag opens in it")
        assertContains(page, """<a href="/" class="brand"><strong>&lt;b&gt;NAME&lt;/b&gt;</strong></a>""")
        assertContains(page, """<a href="/x" aria-current="page">&lt;img src=x onerror=alert(1)&gt;</a>""")
        assertFalse(page.contains("<img src=x"), "nowhere as markup")
        assertContains(page, "Some <strong>words</strong>.", message = "a block's html is markup")
        assertContains(page, """<script src="/js/kroom/domhelper.js""", message = "and so is what the site puts in the head")

        // a region a page #define'd is written where the layout calls it: what it inserts is escaped there
        assertContains(visitor().get("/club/%3Ci%3Eit").bodyAsText(), "<h1>&lt;i&gt;it</h1>")

        for (address in listOf("javascript:alert(1)", "data:text/html,<script>alert(1)</script>", "vbscript:x")) {
            val refused = editor.json(HttpMethod.Put, "/api/site/menu", """{"items":[{"href":${com.republicate.kson.Json.MutableArray().apply { push(address) }.toString().removeSurrounding("[", "]")},"label":{"en":"Elsewhere"}}]}""")
            assertEquals(HttpStatusCode.BadRequest, refused.status, address)
        }
        editor.json(HttpMethod.Put, "/api/site/menu", """{"items":[{"slug":"x"},{"href":"https://example.org/","label":{"en":"Elsewhere"}},{"href":"mailto:someone@example.org","label":{"en":"Write"}}]}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
    }
}
