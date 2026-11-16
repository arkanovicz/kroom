package com.republicate.kroom.webapp.l10n

import com.republicate.kroom.webapp.velocity.installVelocity
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A language's catalog adds up across po files: the application's first, a library's after, the first loaded wins. */
class CatalogTest {

    private fun source() = PoTranslationSource().apply {
        loadBundle("fr", "/catalog/host/fr.po")
        loadBundle("fr", "/catalog/lib/fr.po")
    }

    @Test
    fun `a msgid both files hold keeps the first loaded`() {
        assertEquals("Bienvenue", source().getTranslation("Welcome", "fr"))
    }

    @Test
    fun `a msgid only the second file holds is added`() {
        assertEquals("Au revoir", source().getTranslation("Goodbye", "fr"))
    }

    @Test
    fun `all translations are the sum`() {
        assertEquals(mapOf("Welcome" to "Bienvenue", "Goodbye" to "Au revoir"), source().getAllTranslations("fr"))
    }

    @Test
    fun `a missing file adds nothing and the language counts as loaded`() {
        val source = PoTranslationSource().apply {
            loadBundle("fr", "/catalog/none/fr.po")
            loadBundle("de", "/catalog/none/de.po")
        }
        assertTrue(source.isLoaded("de"))
        assertEquals(emptyMap(), source.getAllTranslations("de"))
        source.loadBundle("fr", "/catalog/host/fr.po")
        source.loadBundle("fr", "/catalog/none/fr.po")
        assertEquals(mapOf("Welcome" to "Bienvenue"), source.getAllTranslations("fr"))
    }

    private fun ApplicationTestBuilder.app(withLibrary: Boolean = true) = application {
        installVelocity { templatePath = null }
        installL10n {
            sourceLanguage = "en"; defaultLanguage = "en"
            languages = mapOf("en" to "English", "fr" to "French")
            i18nPath = "/catalog/host"
        }
        if (withLibrary) assertTrue(addCatalog("/catalog/lib"))
        routing { get("/{...}") { call.respondVelocityTranslated("catalog/page.html") } }
    }

    @Test
    fun `a rendered page sees the application's entries over the library's, and the library's beside them`() = testApplication {
        app()
        assertEquals("<p>Bienvenue</p><p>Au revoir</p>", client.get("/fr/").bodyAsText())
    }

    @Test
    fun `without the library's catalog its entry stays in the source language`() = testApplication {
        var config: L10nConfig? = null
        app(withLibrary = false)
        application { config = l10nConfig }
        startApplication()
        assertNull(config!!.translationSource.getTranslation("Goodbye", "fr"))
        assertEquals("Bienvenue", config!!.translationSource.getTranslation("Welcome", "fr"))
    }

    @Test
    fun `addCatalog answers false with a source that is not po files`() = testApplication {
        var added: Boolean? = null
        application {
            installL10n {
                languages = mapOf("en" to "English", "fr" to "French")
                useSource(object : TranslationSource {
                    override fun getTranslation(en: String, iso: String): String? = null
                    override fun getAllTranslations(iso: String): Map<String, String> = emptyMap()
                    override fun isLoaded(iso: String): Boolean = true
                })
            }
            added = addCatalog("/catalog/lib")
        }
        startApplication()
        assertEquals(false, added)
    }

    @Test
    fun `addCatalog answers false when l10n is not installed`() = testApplication {
        var added: Boolean? = null
        application { added = addCatalog("/catalog/lib") }
        startApplication()
        assertFalse(added!!)
    }
}
