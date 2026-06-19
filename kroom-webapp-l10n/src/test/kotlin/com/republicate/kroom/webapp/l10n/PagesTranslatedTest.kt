package com.republicate.kroom.webapp.l10n

import com.republicate.kroom.webapp.session.installSessions
import com.republicate.kroom.webapp.velocity.installVelocity
import com.republicate.kroom.webapp.velocity.pages
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class PagesTranslatedTest {

    private val source = object : TranslationSource {
        override fun getTranslation(en: String, iso: String): String? =
            if (en == "Hello" && iso == "fr") "Bonjour" else null
        override fun getAllTranslations(iso: String): Map<String, String> =
            if (iso == "fr") mapOf("Hello" to "Bonjour") else emptyMap()
        override fun isLoaded(iso: String): Boolean = true
    }

    // SESSION strategy keeps clean (prefix-less) URLs so pages() resolves pages/about.html directly.
    private fun ApplicationTestBuilder.app() = application {
        installSessions { sessionSecret = "test-secret" }
        installVelocity { templatePath = null; session("user") { "alice" } }
        installL10n {
            sourceLanguage = "en"; defaultLanguage = "en"
            languages = mapOf("en" to "English", "fr" to "French")
            localeStrategy = LocaleStrategy.SESSION
            useSource(source)
        }
        routing { pages() }
    }

    @Test
    fun `installing l10n makes pages() render translated`() = testApplication {
        app()
        val res = client.get("/about") { header(HttpHeaders.AcceptLanguage, "fr") }
        assertEquals("<p>Bonjour</p>alice", res.bodyAsText())
    }

    @Test
    fun `source language pages still get the base context`() = testApplication {
        app()
        assertEquals("<p>Hello</p>alice", client.get("/about").bodyAsText())
    }
}
