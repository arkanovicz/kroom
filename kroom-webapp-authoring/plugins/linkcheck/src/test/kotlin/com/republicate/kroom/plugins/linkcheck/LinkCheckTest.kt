package com.republicate.kroom.plugins.linkcheck

import com.republicate.kroom.webapp.authoring.MemoryStorage
import com.republicate.kroom.webapp.authoring.installContentSite
import com.republicate.kroom.webapp.authoring.site
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class LinkCheckTest {

    @Test
    fun `links are resolved against their page, anchors and mail left out`() {
        assertEquals(setOf("http://s/club/news", "http://s/x", "https://o.org/a?b=1&c=2"),
            LinkCheck.links("""<a href="news">n</a> <a href="/x#frag">x</a> <a href="#top">t</a>
                <a href="mailto:a@b.c">m</a> <a href="https://o.org/a?b=1&amp;c=2">o</a>""", "http://s/club/"))
    }

    @Test
    fun `every page walked, every broken link listed with its page`() = testApplication {
        val storage = MemoryStorage().apply { settings("linkcheck")["baseUrl"] = "http://site" }
        lateinit var check: LinkCheck
        // the site answers through the test client, the rest of the web through this table
        check = LinkCheck { url ->
            if (url.startsWith("http://site")) client.get(url.removePrefix("http://site")).let {
                LinkCheck.Answer(it.status.value, it.bodyAsText().takeIf { _ -> it.contentType()?.match(ContentType.Text.Html) == true })
            } else LinkCheck.Answer(if (url.endsWith("/dead")) 404 else 200)
        }
        application {
            installContentSite { this.storage = storage; sessionSecret = "test-secret"; plugins += check }
        }
        startApplication()
        val broken = check.run(application.site).toSet()
        assertEquals(setOf("/index" to "/gone", "/index" to "https://example.org/dead", "/index" to "/media/missing.png"), broken)
        assertEquals(3, storage.records("linkcheck", "broken").list().size)
    }
}
