package com.republicate.kroom.plugins.webmaster

import com.republicate.kroom.webapp.authoring.MemoryStorage
import com.republicate.kroom.webapp.authoring.installContentSite
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class OutsideTest {

    private val storage = MemoryStorage().apply {
        content.write("pages/club/13Ma/description.md", "Les Vagabonds", emptyMap())
        settings("site")["baseUrl"] = "https://example.org/"
    }

    private fun ApplicationTestBuilder.site() = application {
        installContentSite {
            storage = this@OutsideTest.storage
            sessionSecret = "test-secret"
            plugins += Webmaster()
        }
    }

    @Test
    fun `the sitemap lists concrete pages and the placeholder pages blocks exist for`() = testApplication {
        site()
        val sitemap = client.get("/sitemap.xml").bodyAsText()
        assertContains(sitemap, "<loc>https://example.org/club/13Ma</loc>")
        assertContains(sitemap, "<loc>https://example.org/index</loc>")
        assertFalse(sitemap.contains("/login"), "excluded by default")
        assertContains(client.get("/robots.txt").bodyAsText(), "Sitemap: https://example.org/sitemap.xml")
        assertFalse(client.get("/index").bodyAsText().contains("noindex"))
    }

    @Test
    fun `a page may keep itself out of search, and only that page`() = testApplication {
        site()
        assertContains(client.get("/legal/terms").bodyAsText(), """<meta name="robots" content="noindex, nofollow">""")
        assertFalse(client.get("/index").bodyAsText().contains("noindex"), "one page's word is that page's only")
    }

    @Test
    fun `an unindexed site says so, to robots and in every head`() = testApplication {
        storage.settings("webmaster")["indexed"] = "false"
        site()
        assertContains(client.get("/robots.txt").bodyAsText(), "Disallow: /")
        assertContains(client.get("/index").bodyAsText(), """<meta name="robots" content="noindex, nofollow">""")
    }
}
