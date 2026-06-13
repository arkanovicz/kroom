package com.republicate.kroom.webapp.l10n

import com.republicate.kroom.webapp.session.installSessions
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LocaleStrategyTest {

    private fun ApplicationTestBuilder.l10n(strategy: LocaleStrategy, withSessions: Boolean = false) {
        application {
            if (withSessions) installSessions { sessionSecret = "test-secret" }
            installL10n {
                sourceLanguage = "en"; defaultLanguage = "en"
                languages = mapOf("en" to "English", "fr" to "French")
                localeStrategy = strategy
            }
            routing { get("{...}") { call.respondText(call.language) } }
        }
    }

    private fun ApplicationTestBuilder.noRedirectClient() = createClient {
        install(HttpCookies)
        followRedirects = false
    }

    // --- URL_PREFIX (default, unchanged) ---

    @Test
    fun `URL_PREFIX redirects a prefix-less path to the preferred language`() = testApplication {
        l10n(LocaleStrategy.URL_PREFIX)
        val res = noRedirectClient().get("/x")
        assertEquals(HttpStatusCode.Found, res.status)
        assertEquals("/en/x", res.headers[HttpHeaders.Location])
    }

    @Test
    fun `URL_PREFIX serves a known prefix and exposes call language`() = testApplication {
        l10n(LocaleStrategy.URL_PREFIX)
        val res = noRedirectClient().get("/fr/x")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("fr", res.bodyAsText())
    }

    // --- SESSION ---

    @Test
    fun `SESSION serves a prefix-less path without redirect, language from header`() = testApplication {
        l10n(LocaleStrategy.SESSION, withSessions = true)
        val res = noRedirectClient().get("/x") { header(HttpHeaders.AcceptLanguage, "fr,en;q=0.8") }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("fr", res.bodyAsText())
    }

    @Test
    fun `SESSION pins the language from a prefix then drops it, anonymous`() = testApplication {
        l10n(LocaleStrategy.SESSION, withSessions = true)
        val client = noRedirectClient()

        val pin = client.get("/fr/x")
        assertEquals(HttpStatusCode.Found, pin.status)
        assertEquals("/x", pin.headers[HttpHeaders.Location])

        // follow-up carries the pinned language with no prefix, no UserSession present
        val after = client.get("/x")
        assertEquals(HttpStatusCode.OK, after.status)
        assertEquals("fr", after.bodyAsText())
    }

    @Test
    fun `SESSION strips a bare language segment to root`() = testApplication {
        l10n(LocaleStrategy.SESSION, withSessions = true)
        val res = noRedirectClient().get("/fr")
        assertEquals(HttpStatusCode.Found, res.status)
        assertEquals("/", res.headers[HttpHeaders.Location])
    }

    @Test
    fun `SESSION skips reserved prefixes`() = testApplication {
        l10n(LocaleStrategy.SESSION, withSessions = true)
        val res = noRedirectClient().get("/api/i18n")
        assertEquals(HttpStatusCode.OK, res.status)
        assertNull(res.headers[HttpHeaders.Location])
    }

    @Test
    fun `SESSION without installSessions fails fast at startup`() {
        assertFailsWith<IllegalStateException> {
            testApplication {
                l10n(LocaleStrategy.SESSION, withSessions = false)
                client.get("/x")
            }
        }
    }
}
