package com.republicate.kroom.plugins.redirects

import com.republicate.kroom.webapp.authoring.MemoryStorage
import com.republicate.kroom.webapp.authoring.installContentSite
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class RedirectsTest {

    @Test
    fun `rules match exactly or by prefix, and carry the rest over`() {
        val parsed = Redirects.parse("""
            # moved in 2026
            /old /new
            /clubs/* /club/* 302
            /gone /elsewhere 418
        """.trimIndent())
        assertEquals(listOf("/gone /elsewhere 418"), parsed.errors)
        assertEquals("/new", parsed.rules[0].target("/old"))
        assertEquals(null, parsed.rules[0].target("/older"))
        assertEquals("/club/13Ma", parsed.rules[1].target("/clubs/13Ma"))
    }

    @Test
    fun `a matching request is redirected before any route sees it`() = testApplication {
        val storage = MemoryStorage().apply { settings("redirects")["rules"] = "/old /index\n/clubs/* /club/* 302" }
        application {
            installContentSite {
                this.storage = storage
                sessionSecret = "test-secret"
                plugins += Redirects()
            }
        }
        val browser = createClient { followRedirects = false }
        browser.get("/old").let {
            assertEquals(HttpStatusCode.MovedPermanently, it.status)
            assertEquals("/index", it.headers[HttpHeaders.Location])
        }
        browser.get("/clubs/13Ma").let {
            assertEquals(HttpStatusCode.Found, it.status)
            assertEquals("/club/13Ma", it.headers[HttpHeaders.Location])
        }
        assertEquals(HttpStatusCode.OK, browser.get("/index").status)
    }
}
