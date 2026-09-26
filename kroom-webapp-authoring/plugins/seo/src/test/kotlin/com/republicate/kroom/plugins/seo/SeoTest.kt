package com.republicate.kroom.plugins.seo

import com.republicate.kroom.webapp.authoring.MemoryStorage
import com.republicate.kroom.webapp.authoring.installContentSite
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class SeoTest {

    private val storage = MemoryStorage().apply {
        content.write("pages/club/13Ma/description.md", "Les Vagabonds", emptyMap())
        settings("seo").apply {
            set("baseUrl", "https://example.org/")
            set("description", "Go & more")
        }
    }

    private fun ApplicationTestBuilder.site() = application {
        installContentSite {
            storage = this@SeoTest.storage
            sessionSecret = "test-secret"
            plugins += Seo()
        }
    }

    @Test
    fun `every page carries its description, canonical URL and share tags`() = testApplication {
        site()
        val page = client.get("/club/13Ma").bodyAsText()
        assertContains(page, """<meta name="description" content="Go &amp; more">""")
        assertContains(page, """<link rel="canonical" href="https://example.org/club/13Ma">""")
        assertFalse(page.contains("noindex"))
    }

    @Test
    fun `the sitemap lists concrete pages and the placeholder pages blocks exist for`() = testApplication {
        site()
        val sitemap = client.get("/sitemap.xml").bodyAsText()
        assertContains(sitemap, "<loc>https://example.org/club/13Ma</loc>")
        assertContains(sitemap, "<loc>https://example.org/index</loc>")
        assertFalse(sitemap.contains("/login"), "excluded by default")
        assertContains(client.get("/robots.txt").bodyAsText(), "Sitemap: https://example.org/sitemap.xml")
    }

    @Test
    fun `a page says its own description and title, and may keep itself out of search`() = testApplication {
        site()
        val page = client.get("/legal/terms").bodyAsText()
        assertContains(page, """<meta name="description" content="Qui publie ce site">""")
        assertContains(page, """<meta property="og:title" content="Mentions légales">""")
        assertContains(page, """<meta name="robots" content="noindex, nofollow">""")
        assertFalse(page.contains("Go &amp; more"))
        assertFalse(client.get("/index").bodyAsText().contains("noindex"), "one page's word is that page's only")
    }

    @Test
    fun `an unindexed site says so, to robots and in every head`() = testApplication {
        storage.settings("seo")["indexed"] = "false"
        site()
        assertContains(client.get("/robots.txt").bodyAsText(), "Disallow: /")
        assertContains(client.get("/index").bodyAsText(), """<meta name="robots" content="noindex, nofollow">""")
    }
}
