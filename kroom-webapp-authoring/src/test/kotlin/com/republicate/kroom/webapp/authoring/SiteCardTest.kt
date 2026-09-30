package com.republicate.kroom.webapp.authoring

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/** The site's card in every head: its settings, a page's own words over them. */
class SiteCardTest {

    private val storage = MemoryStorage().apply {
        settings("site").apply {
            set("name", "Les Vagabonds")
            set("baseUrl", "https://example.org/")
            set("description", "Go & more")
        }
    }

    private fun ApplicationTestBuilder.site() = application {
        installContentSite { storage = this@SiteCardTest.storage; sessionSecret = "test-secret" }
    }

    @Test
    fun `every page carries the site's description, name, canonical URL`() = testApplication {
        site()
        val page = client.get("/about").bodyAsText()
        assertContains(page, """<meta name="description" content="Go &amp; more">""")
        assertContains(page, """<meta property="og:site_name" content="Les Vagabonds">""")
        assertContains(page, """<link rel="canonical" href="https://example.org/about">""")
    }

    @Test
    fun `a page says its own title and description, over the site's`() = testApplication {
        site()
        val page = client.get("/legal/terms").bodyAsText()
        assertContains(page, """<meta name="description" content="Qui publie ce site">""")
        assertContains(page, """<meta property="og:title" content="Mentions légales">""")
        assertFalse(page.contains("Go &amp; more"))
        assertContains(client.get("/about").bodyAsText(), "Go &amp; more", message = "one page's word is that page's only")
    }
}
