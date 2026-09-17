package com.republicate.kroom.webapp.velocity

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A page template can be parameterized by its own path — the convention the content tree is built on, where
 * a club's page and the blocks it includes live under the same `_code_` segment. Pinned end to end because
 * the URL→template and template→path directions must stay inverse; [com.republicate.kroom.PathTemplate]
 * pins the pure part.
 */
class PlaceholderPagesTest {

    // templatePath = null so the catalog and the loaders both read test resources at the classpath root.
    private fun ApplicationTestBuilder.app() = application {
        installVelocity { templatePath = null }
        routing { placeholderPages(); pages() }
    }

    @Test
    fun `a placeholder page is mounted as the route it describes`() = testApplication {
        app()
        assertEquals("city paris", client.get("/city/paris").bodyAsText())
    }

    @Test
    fun `an index under a placeholder directory mounts the same route`() = testApplication {
        app()
        assertEquals("shop 7", client.get("/shop/7").bodyAsText())
    }

    @Test
    fun `a concrete page wins over the placeholder`() = testApplication {
        app()
        assertEquals("lyon, capitale des gones", client.get("/city/lyon").bodyAsText())
    }

    @Test
    fun `an unbacked path is still a 404`() = testApplication {
        app()
        assertEquals(HttpStatusCode.NotFound, client.get("/city/paris/extra").status)
    }
}
