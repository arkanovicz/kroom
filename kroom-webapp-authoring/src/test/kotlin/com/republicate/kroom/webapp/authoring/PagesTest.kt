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
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/company/history","label":{"en":"Our history"}}""").let {
            assertEquals(HttpStatusCode.Conflict, it.status, "a page goes under a page")
            assertContains(it.bodyAsText(), """"code":"noParent","args":{"parent":"/company"}""")
        }
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/company","label":{"en":"Company"}}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
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
        assertContains(visitor().get("/about").bodyAsText(), """<li class="section"><a href="/company">Company</a>""", message = "publishing climbs: the draft above went out with it")
        assertEquals(HttpStatusCode.OK, visitor().get("/company").status)
        assertContains(visitor("admin").get("/api/site/menu").bodyAsText(), """"path":"/company/history","kind":"authored","status":"published","layout":"article","movable":true""")
        assertContains(page, """<nav class="west" aria-label="Company">""", message = "an article has its section's menu beside it")
        for (layout in listOf("wide", "gallery")) {
            editor.json(HttpMethod.Put, "/api/site/pages/company/history", """{"layout":"$layout"}""").let { assertEquals(HttpStatusCode.OK, it.status) }
            val other = visitor().get("/company/history").bodyAsText()
            assertContains(other, """data-layout="$layout"""")
            assertEquals(layout == "gallery", other.contains("""class="west""""), "a wide page runs the whole width, no menu; a gallery keeps its section's")
        }
        editor.json(HttpMethod.Put, "/api/site/pages/company/history", """{"layout":"sidebar"}""").let { assertEquals(HttpStatusCode.BadRequest, it.status, "a layout gone from the vocabulary") }
        // a record stored under a former name still serves, as an article
        val records = storage.records("site", "pages")
        records.put("company_-history", com.republicate.kson.Json.MutableObject().apply { putAll(records.get("company_-history")!!); set("layout", "sidebar") })
        assertContains(visitor().get("/company/history").bodyAsText(), """data-layout="article"""")
        assertContains(visitor("admin").get("/api/site/menu").bodyAsText(), """"path":"/company/history","kind":"authored","status":"published","layout":"article"""")

        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/company/history"}""").let { assertEquals(HttpStatusCode.Conflict, it.status) }
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/about"}""").let { assertEquals(HttpStatusCode.Conflict, it.status, "a template's page is not for the taking") }
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/club"}""").let { assertEquals(HttpStatusCode.Conflict, it.status, "the section its instances sit under was given a page at start") }
        assertEquals(HttpStatusCode.OK, visitor().get("/club").status)
        assertContains(editor.get("/club/13Ma").bodyAsText(), "<h1>13Ma</h1>", message = "and its instances answer as before")
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"bad path"}""").let { assertEquals(HttpStatusCode.BadRequest, it.status) }
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/company/old","layout":"default"}""").let { assertEquals(HttpStatusCode.BadRequest, it.status, "default is no layout any more") }
        editor.delete("/api/site/pages/company").let {
            assertEquals(HttpStatusCode.Conflict, it.status, "a page with pages under it stays")
            assertContains(it.bodyAsText(), """"code":"pageHolds"""")
        }
        assertEquals(HttpStatusCode.OK, editor.delete("/api/site/pages/company/history").status, "a leaf goes")
        assertEquals(HttpStatusCode.NotFound, visitor().get("/company/history").status)
        assertEquals("## Since 1998\n\nA long **story**.", storage.content.read("pages/company/history/content.md")!!.body, "its blocks stay")
    }

    @Test
    fun `at start every path with pages under it and none of its own gets one - wide, listing them, signed by kroom`() = testApplication {
        storage.content.write("pages/team/7/roster/roster.md", "Seven **strong**.", mapOf("author" to "ed"))
        storage.records("site", "pages").put("drafts_-one", AuthoredPage("/drafts/one", author = "ed").toJson())
        site()
        val admin = visitor("admin")
        val menu = admin.get("/api/site/menu").bodyAsText()
        // a concrete gap (pages/legal/terms.html), the folder of a placeholder template (pages/club/_club_.html), of a deeper one (pages/team/_id_/roster.html)
        for (path in listOf("/legal", "/club", "/team")) {
            val page = visitor().get(path)
            assertEquals(HttpStatusCode.OK, page.status, path)
            assertContains(page.bodyAsText(), """data-layout="wide"""")
            assertContains(menu, """"path":"$path","kind":"authored","status":"published","layout":"wide"""")
            assertEquals(AuthoredPage.COMPLETER, application.site.authored.get(path)!!.author)
            storage.content.read("pages$path/content.md")!!.let {
                assertEquals(AuthoredPage.TOC, it.body, "its content: the list of the pages under it")
                assertEquals(AuthoredPage.COMPLETER, it.author)
            }
        }
        assertContains(visitor().get("/legal").bodyAsText(), """<h1>Legal</h1>""", message = "its name heads it")
        assertContains(visitor().get("/legal").bodyAsText(), """<li><a href="/legal/terms">Terms</a></li>""", message = "the list, as the page hands it")
        assertContains(visitor().get("/club").bodyAsText(), """<h1>Club</h1>""")
        assertContains(visitor().get("/about").bodyAsText(), """<li class="section"><a href="/legal">Legal</a>""", message = "the menu's entry leads to the page itself")

        // a parameterized address is left alone: no page, in no menu, what is under it served as before
        assertEquals(HttpStatusCode.OK, visitor().get("/team/7/roster").status)
        assertContains(visitor().get("/team/7/roster").bodyAsText(), "Seven <strong>strong</strong>.")
        assertEquals(HttpStatusCode.NotFound, visitor().get("/team/7").status)
        assertNull(application.site.authored.get("/team/7"))
        assertFalse(menu.contains("/team/7"), "nor what is under it")
        assertFalse(admin.get("/about").bodyAsText().contains("/team/7"))

        // a folder of drafts only: born a draft
        assertEquals(HttpStatusCode.NotFound, visitor().get("/drafts").status)
        assertContains(menu, """"path":"/drafts","kind":"authored","status":"draft","layout":"wide"""")

        // again: nothing changes
        val before = application.site.authored.all()
        application.site.authored.complete()
        assertEquals(before, application.site.authored.all())
        assertEquals(1, (storage.content as Versioned).log("pages/legal/content.md").size)
        // what an editor made of it is theirs
        val editor = visitor("ed")
        storage.content.write("pages/legal/content.md", "Our **terms**", mapOf("author" to "ed"))
        editor.json(HttpMethod.Put, "/api/site/pages/legal", """{"layout":"article"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        application.site.authored.complete()
        assertEquals("article", application.site.authored.get("/legal")!!.layout)
        assertEquals("Our **terms**", storage.content.read("pages/legal/content.md")!!.body)

        // it holds pages, a template's route among them: it stays
        for (path in listOf("/legal", "/club", "/team")) {
            editor.delete("/api/site/pages$path").let {
                assertEquals(HttpStatusCode.Conflict, it.status, path)
                assertContains(it.bodyAsText(), """"code":"pageHolds"""")
            }
        }
    }

    @Test
    fun `publishing climbs - a draft above a page going out goes out with it, and a page stays out while visitors reach one under it`() = testApplication {
        site()
        val editor = visitor("ed")
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/news","label":{"en":"News"}}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/news/today","label":{"en":"Today"}}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/news/today/noon","label":{"en":"Noon"},"status":"published"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        for (path in listOf("/news", "/news/today", "/news/today/noon")) assertEquals(HttpStatusCode.OK, visitor().get(path).status, "born published, $path")
        assertContains(visitor().get("/about").bodyAsText(), """<li class="section"><a href="/news">News</a><ul><li class="section"><a href="/news/today">Today</a>""")

        val unpublish = { path: String -> suspend { editor.json(HttpMethod.Put, "/api/site/pages$path", """{"status":"draft"}""") } }
        unpublish("/news")().let {
            assertEquals(HttpStatusCode.Conflict, it.status, "visitors reach pages under it")
            assertContains(it.bodyAsText(), """"code":"pageHolds"""")
        }
        assertEquals(HttpStatusCode.OK, unpublish("/news/today/noon")().status, "a leaf")
        assertEquals(HttpStatusCode.Conflict, unpublish("/news")().status, "/news/today is still out")
        assertEquals(HttpStatusCode.OK, unpublish("/news/today")().status, "all under it are drafts")
        assertEquals(HttpStatusCode.OK, unpublish("/news")().status)
        for (path in listOf("/news", "/news/today", "/news/today/noon")) assertEquals(HttpStatusCode.NotFound, visitor().get(path).status, path)
        assertFalse(visitor().get("/about").bodyAsText().contains("/news"))

        editor.json(HttpMethod.Put, "/api/site/pages/news/today/noon", """{"status":"published"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        for (path in listOf("/news", "/news/today", "/news/today/noon")) assertEquals(HttpStatusCode.OK, visitor().get(path).status, "a PUT climbs too, $path")
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
    fun `a page is born empty, a root page too - and made again where blocks were left, it keeps them`() = testApplication {
        site()
        val editor = visitor("ed")
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/guides","label":{"en":"Guides"},"status":"published"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/guides/first","label":{"en":"First steps"},"status":"published"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        assertNull(storage.content.read("pages/guides/content.md"), "no list of its pages planted there: the section's menu is that")
        assertNull(storage.content.read("pages/guides/first/content.md"))
        assertContains(visitor().get("/guides").bodyAsText(), """<nav class="west" aria-label="Guides">""")

        storage.content.write("pages/guides/content.md", "All our **guides**", mapOf("author" to "ed"))
        assertContains(visitor().get("/guides").bodyAsText(), "All our <strong>guides</strong>")
        assertEquals(HttpStatusCode.Conflict, editor.delete("/api/site/pages/guides").status, "it holds a page")
        assertEquals(HttpStatusCode.OK, editor.delete("/api/site/pages/guides/first").status)
        assertEquals(HttpStatusCode.OK, editor.delete("/api/site/pages/guides").status)
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/guides","status":"published"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        assertContains(visitor().get("/guides").bodyAsText(), "All our <strong>guides</strong>", message = "its block stayed")
    }

    @Test
    fun `a page made where a developer's placeholder answers is the template's - a draft in the menu, written in its blocks, gone with them`() = testApplication {
        site()
        val editor = visitor("ed")
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/club/9Zz","label":{"en":"Nine"},"layout":"gallery"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        assertNull(storage.content.read("pages/club/9Zz/content.md"), "no block of an editor's page planted: its blocks are its template's")
        assertContains(visitor("admin").get("/api/site/menu").bodyAsText(), """"path":"/club/9Zz","kind":"instance","status":"draft","layout":"gallery","movable":false""")
        assertContains(visitor().get("/club/9Zz").bodyAsText(), "<h1>9Zz</h1>", message = "the developer's template lays it out")
        assertFalse(visitor().get("/about").bodyAsText().contains("/club/9Zz"), "a draft is in no visitor's menu")
        assertContains(editor.get("/about").bodyAsText(), """<a href="/club/9Zz" class="draft">Nine</a>""", message = "its editor sees it, by the name it was given")

        editor.json(HttpMethod.Put, "/api/site/pages/club/9Zz", """{"status":"published"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        assertContains(visitor().get("/about").bodyAsText(), """<a href="/club/9Zz">Nine</a>""")
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/club/9Zz"}""").let { assertEquals(HttpStatusCode.Conflict, it.status, "made once") }
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/about"}""").let { assertEquals(HttpStatusCode.Conflict, it.status, "a developer's own page is no place to make one") }
        editor.json(HttpMethod.Post, "/api/site/pages/move", """{"from":"/club/9Zz","to":"/nine"}""").let { assertEquals(HttpStatusCode.Conflict, it.status, "it stays under its template") }

        storage.content.write("pages/club/9Zz/description.md", "Its **words**.", mapOf("author" to "ed"))
        assertContains(visitor().get("/club/9Zz").bodyAsText(), "Its <strong>words</strong>.")
        assertEquals(HttpStatusCode.OK, editor.delete("/api/site/pages/club/9Zz").status)
        assertNull(storage.content.read("pages/club/9Zz/description.md"), "there by its blocks: they went with it")
        assertFalse(visitor("admin").get("/api/site/menu").bodyAsText().contains("/club/9Zz"))
    }

    @Test
    fun `a block of an authored page goes through the editor like any other - lock, preview within its page, submit`() = testApplication {
        site()
        val editor = visitor("ed")
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/company","label":{"en":"Company"}}""")
        val path = "pages/company/content.md"
        val held = editor.post("/api/content/lock/$path")
        assertEquals(HttpStatusCode.OK, held.status, held.bodyAsText())
        // born empty: the editor opens on nothing, and submits over no revision
        assertContains(held.bodyAsText(), """"body":"","rev":""""")
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
        assertEquals(listOf("moved from pages/notes/content.md", null, null), history.map { it.message }, "its past followed (two writes), and the move is in it")
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
        refused("/fresh", "/nowhere/fresh", "no page at /nowhere: a page goes under a page")()
        assertContains(visitor("admin").get("/api/site/menu").bodyAsText(), """"path":"/a","kind":"authored","status":"draft","layout":"article","movable":false""")
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
            editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/company"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
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
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/company"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/company/team","label":{"en":"Team"}}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        storage.content.write("pages/company/team/content-fr.md", "Bonjour l'équipe.", mapOf("author" to "ed"))
        val page = editor.get("/company/team").bodyAsText()
        assertContains(page, "<title>Team</title>")
        assertContains(page, """<main class="custom">""")
        assertContains(page, "Bonjour l'équipe.")
        assertContains(page, """data-content="pages/company/team/content-fr.md"""", message = "the block the application named, edited in place like any other")
        assertContains(page, "<em>Nothing beside Team yet.</em>", message = "a body stands in for a block nobody wrote")
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/company/lab","label":{"en":"R&D <b>"}}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        assertContains(editor.get("/company/lab").bodyAsText(), "<em>Nothing beside R&amp;D &lt;b&gt; yet.</em>", message = "it is the page's text: what it inserts is escaped where it lands")
    }

    @Test
    fun `a page born with a layout a plugin seeds starts as the plugin's call, any other empty`() = testApplication {
        val seeder = object : Plugin {
            override val id = "seeder"
            override fun install(site: Site) { site.seed("gallery") { page -> "\$gallery.show(\"${page.path.trim('/')}\")" } }
        }
        site(null, seeder)
        val editor = visitor("ed")
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/photos","layout":"gallery"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        assertEquals("\$gallery.show(\"photos\")", storage.content.read("pages/photos/content.md")!!.body)
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/photos/plage","layout":"gallery"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        assertEquals("\$gallery.show(\"photos/plage\")", storage.content.read("pages/photos/plage/content.md")!!.body)
        assertEquals("ed", storage.content.read("pages/photos/plage/content.md")!!.author, "the page's author signs the seed")
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/crew"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        assertNull(storage.content.read("pages/crew/content.md"))
        assertNull(storage.content.read("pages/photos/plage/east.md"))
    }

    @Test
    fun `what is typed is data - a label, the site's name, a path segment are escaped where a page shows them, and a menu link runs nothing`() = testApplication {
        site()
        val editor = visitor("ed")
        storage.settings("site")["name"] = "<b>NAME</b>"
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/x","label":{"en":"<img src=x onerror=alert(1)>"}}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        storage.content.write("pages/x/content.md", "Some **words**.<script>alert(2)</script> <b onclick=\"alert(3)\">Bold</b>", mapOf("author" to "ed"))
        editor.json(HttpMethod.Put, "/api/site/pages/x", """{"status":"published"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }

        val page = visitor().get("/x").bodyAsText()
        assertContains(page, "<title>&lt;img src=x onerror=alert(1)> — &lt;b>NAME&lt;/b></title>", message = "a title is text up to its closing tag: no tag opens in it")
        assertContains(page, """<a href="/" class="brand"><strong>&lt;b&gt;NAME&lt;/b&gt;</strong></a>""")
        assertContains(page, """<a href="/x" aria-current="page">&lt;img src=x onerror=alert(1)&gt;</a>""")
        assertFalse(page.contains("<img src=x"), "nowhere as markup")
        assertContains(page, "Some <strong>words</strong>. <b>Bold</b>", message = "a block's html is markup, less what would run")
        assertFalse(page.contains("alert(2)") || page.contains("alert(3)"))
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
