package com.republicate.kroom.webapp.velocity

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
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
}
