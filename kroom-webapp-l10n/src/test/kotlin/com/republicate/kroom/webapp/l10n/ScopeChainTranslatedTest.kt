package com.republicate.kroom.webapp.l10n

import com.republicate.kroom.webapp.velocity.installVelocity
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ScopeChainTranslatedTest {

    private val source = object : TranslationSource {
        override fun getTranslation(en: String, iso: String): String? =
            if (en == "Hello" && iso == "fr") "Bonjour" else null
        override fun getAllTranslations(iso: String): Map<String, String> =
            if (iso == "fr") mapOf("Hello" to "Bonjour") else emptyMap()
        override fun isLoaded(iso: String): Boolean = true
    }

    private fun ApplicationTestBuilder.app() = application {
        installVelocity { templatePath = null; session("user") { "alice" } }
        installL10n {
            sourceLanguage = "en"; defaultLanguage = "en"
            languages = mapOf("en" to "English", "fr" to "French")
            useSource(source)
        }
        routing { get("/{...}") { call.respondVelocityTranslated("trans.vm") } }
    }

    @Test
    fun `translated render sees the base context and still translates`() = testApplication {
        app()
        assertEquals("<p>Bonjour</p>alice", client.get("/fr/").bodyAsText())
    }

    @Test
    fun `source language is untranslated but still gets the base context`() = testApplication {
        app()
        assertEquals("<p>Hello</p>alice", client.get("/en/").bodyAsText())
    }
}
