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

    /** The same resolution, reusable outside a route — an editor rendering a page to preview a block in it. */
    @Test
    fun `resolvePage answers the template and what its path binds`() = testApplication {
        application {
            installVelocity { templatePath = null }
            assertEquals("pages/city/_name_.html" to mapOf("name" to "paris"), resolvePage("/city/paris"))
            assertEquals("pages/shop/_id_/index.html" to mapOf("id" to "7"), resolvePage("/shop/7"))
            assertEquals("pages/city/lyon.html" to emptyMap(), resolvePage("/city/lyon"))
            assertEquals(null, resolvePage("/city/paris/extra"))
            // as served: the root is the index, a folder its own
            assertEquals("pages/index.html" to emptyMap(), resolvePage("/"))
            assertEquals("pages/docs/index.html" to emptyMap(), resolvePage("/docs"))
        }
        client.get("/city/paris")   // the application is only built on first call
    }

    /** A partial (`header.inc.html`) is no page: routing refuses a dotted segment, and the catalog follows it. */
    @Test
    fun `a partial is neither routed nor catalogued, under a placeholder or not`() = testApplication {
        application {
            installVelocity { templatePath = null }
            routing { placeholderPages(); pages() }
            val catalog = pageCatalog()
            assertEquals(null, catalog.keys.firstOrNull { ".inc" in it }, "catalog: $catalog")
        }
        assertEquals(HttpStatusCode.NotFound, client.get("/city/paris/header.inc").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/footer.inc").status)
    }

    @Test
    fun `a private directory is neither routed nor catalogued, unless the application says otherwise`() = testApplication {
        application {
            installVelocity { templatePath = null }
            routing { pages() }
            assertEquals(null, pageCatalog().keys.firstOrNull { "/inc/" in it })
            assertEquals(null, resolvePage("/inc/footer"))
        }
        assertEquals(HttpStatusCode.NotFound, client.get("/inc/footer").status)
    }

    @Test
    fun `privateSegments is the application's`() = testApplication {
        application {
            installVelocity { templatePath = null; privateSegments = emptySet() }
            routing { pages() }
        }
        assertEquals("inc partial", client.get("/inc/footer").bodyAsText().trim())
    }

    @Test
    fun `an unbacked path is still a 404`() = testApplication {
        app()
        assertEquals(HttpStatusCode.NotFound, client.get("/city/paris/extra").status)
    }
}
