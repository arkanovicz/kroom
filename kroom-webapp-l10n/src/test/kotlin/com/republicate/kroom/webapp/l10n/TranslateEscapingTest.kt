package com.republicate.kroom.webapp.l10n

import com.republicate.kroom.webapp.velocity.installVelocity
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

/** A `#translate`d fragment is escaped as a `#parse`d one: what someone typed never reaches the page as markup. */
class TranslateEscapingTest {

    private val typed = """<i id="esc-probe">pro"be</i>"""

    private val source = object : TranslationSource {
        override fun getTranslation(en: String, iso: String): String? =
            if (en == "Hello" && iso == "fr") "Bonjour" else null
        override fun getAllTranslations(iso: String): Map<String, String> =
            if (iso == "fr") mapOf("Hello" to "Bonjour") else emptyMap()
        override fun isLoaded(iso: String): Boolean = true
    }

    private fun ApplicationTestBuilder.app() = application {
        installVelocity { templatePath = null }
        installL10n {
            sourceLanguage = "en"; defaultLanguage = "en"
            languages = mapOf("en" to "English", "fr" to "French")
            useSource(source)
        }
        routing { get("/{...}") { call.respondVelocityTranslated("escape/page.html", mapOf("v" to typed)) } }
    }

    private val shown = "&lt;i id=&quot;esc-probe&quot;&gt;pro&quot;be&lt;/i&gt;"

    private fun String.inside(id: String) = substringAfter("<div id=\"$id\">").substringBefore("</div>")

    @Test
    fun `the source language's fragment, handed back untouched, is escaped as parsed`() = testApplication {
        app()
        val body = client.get("/en/").bodyAsText()
        assertEquals("<b>Hello</b><p title=\"$shown\">$shown</p>", body.inside("parsed"))
        assertEquals(body.inside("parsed"), body.inside("translated"))
    }

    @Test
    fun `a translated fragment is escaped as parsed`() = testApplication {
        app()
        val body = client.get("/fr/").bodyAsText()
        assertEquals("<b>Bonjour</b><p title=\"$shown\">$shown</p>", body.inside("translated"))
    }
}
