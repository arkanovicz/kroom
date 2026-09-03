package com.republicate.kroom.webapp.l10n

import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateTranslatorTest {

    private fun source(pairs: Array<out Pair<String, String>>, onMissing: (String) -> Unit) =
        object : TranslationSource {
            private val fr = pairs.toMap()
            override fun getTranslation(en: String, iso: String): String? = if (iso == "fr") fr[en] else null
            override fun getAllTranslations(iso: String): Map<String, String> = if (iso == "fr") fr else emptyMap()
            override fun isLoaded(iso: String): Boolean = true
            override fun onMissing(en: String, iso: String, source: String?) = onMissing(en)
        }

    private fun translator(vararg pairs: Pair<String, String>, onMissing: (String) -> Unit = {}) =
        TemplateTranslator(source(pairs, onMissing))

    @Test
    fun `translates visible text`() {
        assertEquals(
            "<p>Bonjour</p>",
            translator("Hello" to "Bonjour").translate("<p>Hello</p>", "fr")
        )
    }

    @Test
    fun `leaves references verbatim`() {
        assertEquals(
            "<p>Bonjour</p>\$user.name",
            translator("Hello" to "Bonjour").translate("<p>Hello</p>\$user.name", "fr")
        )
    }

    @Test
    fun `leaves directives verbatim and recurses into their blocks`() {
        assertEquals(
            "#if(\$show)<p>Bonjour</p>#end",
            translator("Hello" to "Bonjour").translate("#if(\$show)<p>Hello</p>#end", "fr")
        )
    }

    @Test
    fun `recurses into foreach body`() {
        assertEquals(
            "#foreach(\$i in \$list)<li>Bonjour</li>#end",
            translator("Hello" to "Bonjour").translate("#foreach(\$i in \$list)<li>Hello</li>#end", "fr")
        )
    }

    @Test
    fun `translates title alt and aria-label attributes`() {
        val t = translator("Home" to "Accueil", "Avatar" to "Portrait", "Close" to "Fermer")
        assertEquals("<button title=\"Accueil\"></button>", t.translate("<button title=\"Home\"></button>", "fr"))
        assertEquals("<img alt=\"Portrait\">", t.translate("<img alt=\"Avatar\">", "fr"))
        assertEquals("<a aria-label=\"Fermer\">x</a>", t.translate("<a aria-label=\"Close\">x</a>", "fr"))
    }

    @Test
    fun `leaves parse directive target untouched`() {
        assertEquals(
            "#parse(\"templates/quiz.html\")<p>Bonjour</p>",
            translator("Hello" to "Bonjour").translate("#parse(\"templates/quiz.html\")<p>Hello</p>", "fr")
        )
    }

    @Test
    fun `rewrites literal parse and include targets, leaving dynamic ones`() {
        val t = translator("Hello" to "Bonjour")
        assertEquals(
            "#parse(\"fr/quiz.html\")<p>Bonjour</p>#include(\"fr/foot.html\")",
            t.translate("#parse(\"quiz.html\")<p>Hello</p>#include(\"foot.html\")", "fr") { "fr/$it" }
        )
        // Source language: no text translation, but targets still get the prefix.
        assertEquals(
            "#parse(\"en/quiz.html\")<p>Hello</p>",
            t.translate("#parse(\"quiz.html\")<p>Hello</p>", "en") { "en/$it" }
        )
    }

    @Test
    fun `source language returns source unchanged`() {
        val src = "<p>Hello</p>\$user #if(\$x)<b>Home</b>#end"
        assertEquals(src, translator("Hello" to "Bonjour").translate(src, "en"))
    }

    // K1 repro (mya): fr tree with an EMPTY translation map came out mangled —
    // #end→#en, #if($quizzEnabled) split, stray '>' injected, &#x25B6;→&amp;#x25B6;.
    // With no translations, translate() must be the identity on every byte.
    @Test
    fun `empty translation map is byte-identical on directives entities and refs`() {
        val src = """
            |<button onclick="toggle()">&#x25B6; Play</button>
            |#if(${'$'}quizzEnabled)
            |<div class="quizz">${'$'}score points</div>
            |#end
            |<span>tail</span>
        """.trimMargin()
        assertEquals(src, translator().translate(src, "fr"))
    }

    // A leading character reference is decoration, not phrase: it stays out of the key and comes
    // back verbatim (never double-escaped to &amp;#…). Both spellings key on the same token, though
    // only the hex one splits the text node (`#x25B6` lexes as a macro call).
    @Test
    fun `a leading character reference stays out of the key and intact in the output`() {
        for (ref in listOf("&#x25B6;", "&#182;")) {
            assertEquals(
                "<button>$ref Lire</button>",
                translator("Play" to "Lire").translate("<button>$ref Play</button>", "fr")
            )
        }
    }

    // The mya shape: a character reference alone in its element is not a translatable token at all
    // (neither half of the split is), so nothing is looked up and nothing reported missing.
    @Test
    fun `a lone character reference yields no token`() {
        val missing = mutableListOf<String>()
        val t = translator(onMissing = { missing += it })
        assertEquals("<span>&#x25B6;</span>", t.translate("<span>&#x25B6;</span>", "fr"))
        assertEquals(emptyList(), missing)
    }

    @Test
    fun `untranslated text is left in place`() {
        // "World" has no entry → stays; only "Hello" flips.
        assertEquals(
            "<p>Bonjour</p><p>World</p>",
            translator("Hello" to "Bonjour").translate("<p>Hello</p><p>World</p>", "fr")
        )
    }
}
