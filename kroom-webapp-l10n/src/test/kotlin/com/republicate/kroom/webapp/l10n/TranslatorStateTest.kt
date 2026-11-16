package com.republicate.kroom.webapp.l10n

import com.republicate.kroom.webapp.velocity.installVelocity
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A translator leaves nothing behind: a template is read on its own, and no page's translator outlives its answer. */
class TranslatorStateTest {

    private val source = object : TranslationSource {
        override fun getTranslation(en: String, iso: String): String? =
            if (en == "Hello" && iso == "fr") "Bonjour" else null
        override fun getAllTranslations(iso: String): Map<String, String> =
            if (iso == "fr") mapOf("Hello" to "Bonjour") else emptyMap()
        override fun isLoaded(iso: String): Boolean = true
    }

    private var left: Translator? = null

    private fun ApplicationTestBuilder.app() = application {
        installVelocity { templatePath = null }
        installL10n {
            sourceLanguage = "en"; defaultLanguage = "en"
            languages = mapOf("en" to "English", "fr" to "French")
            useSource(source)
        }
        routing {
            get("/{...}") {
                try { call.respondVelocityTranslated("fragment/open.html") } finally { left = Translator.current.get() }
            }
        }
    }

    @Test
    fun `a fragment is read on its own, whatever its page left open`() = testApplication {
        app()
        assertEquals("<b>Bonjour</b><pre>Hello", client.get("/fr/").bodyAsText())
    }

    @Test
    fun `no translator stays on the thread once the page is answered`() = testApplication {
        app()
        client.get("/fr/")
        assertNull(left)
    }
}
