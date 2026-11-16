package com.republicate.kroom.webapp.l10n

import com.republicate.kroom.webapp.velocity.installVelocity
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

/** `$jsTranslations` is the catalog itself: a page's script receives an object, nothing to parse. */
class JsTranslationsTest {

    private val french = mapOf("Back" to "Retour", "Close" to "Fermer </script> \"ici\"")

    private val source = object : TranslationSource {
        override fun getTranslation(en: String, iso: String): String? = if (iso == "fr") french[en] else null
        override fun getAllTranslations(iso: String): Map<String, String> = if (iso == "fr") french else emptyMap()
        override fun isLoaded(iso: String): Boolean = true
    }

    private fun ApplicationTestBuilder.app() = application {
        installVelocity { templatePath = null }
        installL10n {
            sourceLanguage = "en"; defaultLanguage = "en"
            languages = mapOf("en" to "English", "fr" to "French")
            useSource(source)
        }
        routing { get("/{...}") { call.respondVelocityTranslated("script/page.html") } }
    }

    @Test
    fun `a script receives the catalog as an object, what it holds unable to end the script`() = testApplication {
        app()
        val body = client.get("/fr/").bodyAsText().lines()
        assertEquals(
            """<script>window.i18n = {"Back":"Retour","Close":"Fermer \u003c\/script\u003e \"ici\""};</script>""",
            body[0]
        )
        // in an attribute its json text, for a JSON.parse of the dataset; in a handler the literal, then entities
        assertEquals(
            """<body data-i18n="{&quot;Back&quot;:&quot;Retour&quot;,&quot;Close&quot;:&quot;Fermer &lt;/script&gt; \&quot;ici\&quot;&quot;}" """ +
                """onload="start({&quot;Back&quot;:&quot;Retour&quot;,&quot;Close&quot;:&quot;Fermer \u003c\/script\u003e \&quot;ici\&quot;&quot;})"></body>""",
            body[1]
        )
    }

    @Test
    fun `the source language's catalog is an empty object`() = testApplication {
        app()
        assertEquals("<script>window.i18n = {};</script>", client.get("/en/").bodyAsText().lines()[0])
    }
}
