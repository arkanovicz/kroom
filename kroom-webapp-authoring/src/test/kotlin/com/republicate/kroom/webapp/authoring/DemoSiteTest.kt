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

/**
 * The whole point, end to end: a placeholder page renders a block the author has never written, that author
 * edits it in place, and the very next visitor gets the new text — one tree, read by the renderer and
 * written by the editor, with no publication step in between. The demo of the pitch, run as a test.
 */
class DemoSiteTest {

    private val store = VersionedMemoryResourceStore()

    private fun ApplicationTestBuilder.site() = application {
        installContentSite {
            store = this@DemoSiteTest.store
            sessionSecret = "demo-secret"
            placeholder = "*Pas encore de contenu pour **\$name**.*"
            canEdit = { session, _ -> session?.id == "admin" }
        }
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
        val page = visitor("admin").get("/club/13Ma").bodyAsText()
        assertContains(page, "Pas encore de contenu pour <strong>description</strong>")
        assertContains(page, """data-content="pages/club/13Ma/description.md"""")
        assertContains(page, "kroom-edit")   // the handle, because this session may edit

        val anonymous = visitor().get("/club/13Ma").bodyAsText()
        assertContains(anonymous, "Pas encore de contenu")
        assert(!anonymous.contains("kroom-edit")) { "a visitor who cannot edit is shown no handle" }
    }

    @Test
    fun `what an author submits is what the next visitor reads`() = testApplication {
        site()
        val author = visitor("admin")
        val path = "pages/club/13Ma/description.md"

        val held = author.post("/api/content/lock/$path")
        assertEquals(HttpStatusCode.OK, held.status)

        val rev = Regex(""""rev"\s*:\s*"([^"]*)"""").find(held.bodyAsText())!!.groupValues[1]
        val submitted = author.post("/api/content/$path") {
            contentType(ContentType.Application.Json)
            setBody("""{"rev":"$rev","body":"## Les Vagabonds\n\nOn joue le mardi, salle **Jean Moulin**."}""")
        }
        assertEquals(HttpStatusCode.OK, submitted.status)

        val page = visitor().get("/club/13Ma").bodyAsText()
        assertContains(page, "<h2>Les Vagabonds</h2>")
        assertContains(page, "salle <strong>Jean Moulin</strong>")

        // and the store kept who wrote it, and when — the journal a site-wide log reads
        assertEquals("admin", store.read(path)!!.author)
        assertEquals(listOf(path), store.log().map { it.path })
    }

    @Test
    fun `the page is routed by its placeholder, and its blocks follow the same value`() = testApplication {
        site()
        store.write("pages/club/22Ly/description.md", "Le club de Lyon.", mapOf("author" to "admin"))
        val page = visitor().get("/club/22Ly").bodyAsText()
        assertContains(page, "Le club de Lyon.")
        assertContains(page, "<h1>22Ly</h1>")   // the route's own binding, in the page's context
    }
}
