package com.republicate.kroom.webapp.velocity

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class PagesTest {

    // templatePath = null so getTemplate/resourceExists resolve test resources at the classpath root.
    private fun ApplicationTestBuilder.app() = application {
        installVelocity { templatePath = null; session("user") { "alice" } }
        routing { pages() }
    }

    @Test
    fun `a clean URI renders its template, with the base context applied`() = testApplication {
        app()
        assertEquals("src:alice", client.get("/source").bodyAsText())
    }

    @Test
    fun `nested paths resolve nested templates`() = testApplication {
        app()
        assertEquals("terms:alice", client.get("/legal/terms").bodyAsText())
    }

    @Test
    fun `a path with no backing template is 404, not 500`() = testApplication {
        app()
        assertEquals(HttpStatusCode.NotFound, client.get("/about").status)
    }

    @Test
    fun `partials, dotfiles, foreign extensions and traversal are all rejected`() = testApplication {
        app()
        // none of these may escape the prefix or serve a partial — all 404
        for (path in listOf("/header.inc", "/foo.txt", "/.env", "/legal/..%2f..%2fsecret", "/kroom-macros.vtl")) {
            assertEquals(HttpStatusCode.NotFound, client.get(path).status, "expected 404 for $path")
        }
    }

    @Test
    fun `pageRenderer can be set declaratively in the install block`() = testApplication {
        application {
            installVelocity {
                templatePath = null
                session("user") { "alice" }
                pageRenderer = { respondText("custom:$it") }
            }
            routing { pages() }
        }
        assertEquals("custom:pages/source.html", client.get("/source").bodyAsText())
    }

    // The motivating case: a root param route ranks above the tailcard, so pages() never runs.
    // The handler delegates its no-match branch to servePage instead.
    @Test
    fun `a param route can delegate its no-match branch to servePage`() = testApplication {
        application {
            installVelocity { templatePath = null; session("user") { "alice" } }
            routing {
                get("/{community}") {
                    val community = call.parameters["community"]!!
                    if (community == "acme") call.respondText("community:acme")
                    else if (!call.servePage(community)) call.respond(HttpStatusCode.NotFound)
                }
            }
        }
        assertEquals("community:acme", client.get("/acme").bodyAsText())   // real community wins
        assertEquals("src:alice", client.get("/source").bodyAsText())      // falls through to a page
        assertEquals(HttpStatusCode.NotFound, client.get("/nope").status)  // neither → 404
        assertEquals(HttpStatusCode.NotFound, client.get("/header.inc").status) // partial stays unreachable
    }
}
