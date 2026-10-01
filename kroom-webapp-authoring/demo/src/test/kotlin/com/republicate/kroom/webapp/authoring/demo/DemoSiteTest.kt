package com.republicate.kroom.webapp.authoring.demo

import com.republicate.kroom.webapp.authoring.MemoryStorage
import com.republicate.kroom.webapp.authoring.VersionedMemoryResourceStore

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

/**
 * The whole point, end to end: a placeholder page renders a block the author has never written, that author
 * edits it in place, and the very next visitor gets the new text — one tree, read by the renderer and
 * written by the editor, with no publication step in between. The demo of the pitch, run as a test.
 */
class DemoSiteTest {

    private val store = VersionedMemoryResourceStore()

    private fun ApplicationTestBuilder.site() = application {
        demo(MemoryStorage(this@DemoSiteTest.store))
        routing {
            get("/login/{who}") {
                val who = call.parameters["who"]!!
                call.sessions.set(UserSession(who, who, null, "demo"))
                call.respondText("ok")
            }
        }
    }

    private suspend fun ApplicationTestBuilder.visitor(name: String? = null): HttpClient =
        createClient { install(HttpCookies) }.also { if (name != null) it.get("/login/$name") }

    @Test
    fun `an unwritten block renders its placeholder, and the author is offered the way in`() = testApplication {
        site()
        val page = visitor("admin").get("/topics/go").bodyAsText()
        assertContains(page, "Nothing here yet for <strong>body</strong>")
        assertContains(page, """data-content="pages/topics/go/body.md"""")
        assertContains(page, "kroom-edit")   // the handle, because this session may edit
        // `$authoring.assets.tags()` relies on velocity honouring a kotlin default argument
        assertContains(page, "/js/authoring.js?v=")

        val anonymous = visitor().get("/topics/go").bodyAsText()
        assertContains(anonymous, "Nothing here yet")
        assert(!anonymous.contains("kroom-edit")) { "a visitor who cannot edit is shown no handle" }
    }

    @Test
    fun `what an author submits is what the next visitor reads`() = testApplication {
        site()
        val author = visitor("admin")
        val path = "pages/topics/go/body.md"

        val held = author.post("/api/content/lock/$path")
        assertEquals(HttpStatusCode.OK, held.status)

        val rev = Regex(""""rev"\s*:\s*"([^"]*)"""").find(held.bodyAsText())!!.groupValues[1]
        val submitted = author.post("/api/content/$path") {
            contentType(ContentType.Application.Json)
            setBody("""{"page":"/topics/go","rev":"$rev","body":"## Go\n\nA game of **stones**, on a board of lines."}""")
        }
        assertEquals(HttpStatusCode.OK, submitted.status)

        val page = visitor().get("/topics/go").bodyAsText()
        assertContains(page, "<h2>Go</h2>")
        assertContains(page, "A game of <strong>stones</strong>")

        // and the store kept who wrote it, and when — the journal a site-wide log reads
        assertEquals("admin", store.read(path)!!.author)
        assertContains(store.log().map { it.path }, path)
    }

    /** The preview is the page itself: same layout, same context, the unsaved text standing in. */
    @Test
    fun `a preview renders the page with the draft in place, and writes nothing`() = testApplication {
        site()
        val author = visitor("admin")
        val path = "pages/topics/go/body.md"
        author.post("/api/content/lock/$path")

        val preview = author.post("/api/content/preview/$path") {
            contentType(ContentType.Application.Json)
            setBody("""{"page":"/topics/go","body":"## Titre\n\nbrouillon *en cours*"}""")
        }
        assertEquals(HttpStatusCode.OK, preview.status)
        val html = preview.bodyAsText()
        assertContains(html, "brouillon <em>en cours</em>")
        assertContains(html, "<h1>go</h1>")            // the page, not a fragment
        assertEquals(null, store.read(path))           // and nothing was written
    }

    /**
     * A body that would break the page is refused before it is published — by rendering the page as the
     * preview does, the draft validated against what the page passes it — and the author is told why.
     */
    @Test
    fun `a body that would break the page is refused, positioned, and nothing is written`() = testApplication {
        site()
        val author = visitor("admin")
        val path = "pages/topics/go/body.md"
        author.post("/api/content/lock/$path")
        val broken = """{"page":"/topics/go","rev":"","body":"## ${'$'}topic\n\n%if(false)${'$'}secret%end"}"""

        for (route in listOf("preview/$path", path)) {
            val answer = author.post("/api/content/$route") {
                contentType(ContentType.Application.Json)
                setBody(broken)
            }
            assertEquals(HttpStatusCode.UnprocessableEntity, answer.status, route)
            assertContains(answer.bodyAsText(), "$path: line 3, column 11: undeclared reference ${'$'}secret")
        }
        assertEquals(null, store.read(path))
    }

    @Test
    fun `the root is the home page, its blocks seeded, its menu derived from the pages`() = testApplication {
        site()
        val home = visitor().get("/").bodyAsText()
        assertContains(home, "<h2>kroom</h2>")
        assertContains(home, """<a href="/contact">Contact</a>""")
        assertContains(visitor().get("/contact").bodyAsText(), """<form class="kroom-form"""")   // the forms plugin, from a block
    }

    @Test
    fun `the page is routed by its placeholder, and its blocks follow the same value`() = testApplication {
        site()
        store.write("pages/topics/lyon/body.md", "Le club de Lyon.", mapOf("author" to "admin"))
        val page = visitor().get("/topics/lyon").bodyAsText()
        assertContains(page, "Le club de Lyon.")
        assertContains(page, "<h1>lyon</h1>")   // the route's own binding, in the page's context
    }
}
