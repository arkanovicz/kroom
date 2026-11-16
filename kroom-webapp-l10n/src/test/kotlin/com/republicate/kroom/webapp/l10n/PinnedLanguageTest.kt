package com.republicate.kroom.webapp.l10n

import com.republicate.kroom.webapp.session.installSessions
import com.republicate.kroom.webapp.velocity.installVelocity
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `call.language = …` pins a request's language, whatever the session says. */
class PinnedLanguageTest {

    private fun ApplicationTestBuilder.app() {
        application {
            installSessions { sessionSecret = "test-secret" }
            installVelocity { templatePath = null }
            installL10n {
                sourceLanguage = "en"; defaultLanguage = "en"
                languages = mapOf("en" to "English", "fr" to "French")
                localeStrategy = LocaleStrategy.SESSION
                i18nPath = "/catalog/host"
            }
            routing {
                get("/lang") { call.respondText(call.language) }
                get("/pin/{lang}") {
                    call.language = call.parameters["lang"]!!
                    call.respondVelocityTranslated("pinned/page.html")
                }
                get("/unknown") {
                    call.respondText(runCatching { call.language = "de" }.exceptionOrNull()?.javaClass?.simpleName ?: "none")
                }
            }
        }
    }

    private fun ApplicationTestBuilder.cookieClient() = createClient { install(HttpCookies) }

    private fun String.inside(id: String) = substringAfter("<div id=\"$id\">").substringBefore("</div>")

    @Test
    fun `a French session gets the page pinned to English in English`() = testApplication {
        app()
        val client = cookieClient()
        client.get("/fr/")
        assertEquals("fr", client.get("/lang").bodyAsText())
        val body = client.get("/pin/en").bodyAsText()
        assertTrue(body.startsWith("<p>Welcome</p>"), body)
        assertEquals("en", body.inside("lang"))
    }

    @Test
    fun `a visitor with no French preference gets the page pinned to French in French`() = testApplication {
        app()
        val client = cookieClient()
        assertEquals("en", client.get("/lang").bodyAsText())
        val body = client.get("/pin/fr").bodyAsText()
        assertTrue(body.startsWith("<p>Bienvenue</p>"), body)
        assertEquals("fr", body.inside("lang"))
        assertTrue("Bienvenue" in body.inside("js"), body)
    }

    @Test
    fun `pinning a language that is not configured is refused`() = testApplication {
        app()
        assertEquals("IllegalArgumentException", client.get("/unknown").bodyAsText())
    }
}
