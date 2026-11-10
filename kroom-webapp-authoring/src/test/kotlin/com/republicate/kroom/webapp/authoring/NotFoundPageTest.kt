package com.republicate.kroom.webapp.authoring

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** A page nobody answered is a page of the site; anything else a browser did not ask as a page stays a plain 404. */
class NotFoundPageTest {

    private val redirecting = object : Plugin {
        override val id = "late"
        override fun install(site: Site) {
            site.notFound { call -> if (call.request.local.uri == "/late") call.respondRedirect("/about") }
        }
    }

    private fun ApplicationTestBuilder.site(template: String? = "kroom/not_found.html") = application {
        installContentSite {
            sessionSecret = "test-secret"
            warmUp = false
            notFoundTemplate = template
            plugins += redirecting
        }
    }

    private suspend fun ApplicationTestBuilder.browse(url: String, accept: String = "text/html,application/xhtml+xml,*/*;q=0.8") =
        createClient { followRedirects = false }.get(url) { header(HttpHeaders.Accept, accept) }

    @Test
    fun `a page miss answers 404 in the site's layout, with its menu and a title`() = testApplication {
        site()
        val response = browse("/nowhere")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters())
        val page = response.bodyAsText()
        assertContains(page, "<title>Not found — kroom</title>")
        assertContains(page, """class="brand"><strong>kroom</strong>""")
        assertContains(page, """<a href="/about">""", message = "the menu")
        assertContains(page, "<code>/nowhere</code>")
        assertContains(page, """<a href="/">""")
    }

    @Test
    fun `a notFound handler that redirects still wins`() = testApplication {
        site()
        browse("/late").let {
            assertEquals(HttpStatusCode.Found, it.status)
            assertEquals("/about", it.headers[HttpHeaders.Location])
        }
    }

    @Test
    fun `an api miss, an asset miss and a non-page request stay plain`() = testApplication {
        site()
        for ((url, accept) in listOf("/api/nothing" to "text/html", "/css/x.css" to "text/html", "/pictures/x.png" to "image/*", "/nowhere" to "application/json")) {
            browse(url, accept).let {
                assertEquals(HttpStatusCode.NotFound, it.status, url)
                assertEquals("Not Found", it.bodyAsText(), url)
            }
        }
    }

    @Test
    fun `a hostile path is shown as text, never as markup`() = testApplication {
        site()
        val page = browse("/%3Cscript%3Ealert(1)%3C%2Fscript%3E").bodyAsText()
        assertFalse(page.contains("<script>alert(1)"), page)
        assertContains(page, "&lt;script&gt;alert(1)")
    }

    @Test
    fun `a not-found page that fails to render leaves the plain 404`() = testApplication {
        site("kroom/missing.html")
        browse("/nowhere").let {
            assertEquals(HttpStatusCode.NotFound, it.status)
            assertEquals("Not Found", it.bodyAsText())
        }
    }
}
