package com.republicate.kroom.webapp.l10n

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class TranslateTreeTest {
    @Test fun `a tree per language, the source verbatim`() {
        val root = Files.createTempDirectory("tree").toFile()
        val templates = root.resolve("pages").apply { mkdirs() }
        templates.resolve("lobby.html").writeText("<h3>Find Match</h3>#parse(\"part.html\")")
        templates.resolve("part.html").writeText("<p>\$n waiting)</p>")
        val i18n = root.resolve("i18n").apply { mkdirs() }
        i18n.resolve("fr.po").writeText("msgid \"Find Match\"\nmsgstr \"Trouver un match\"\n\nmsgid \"waiting)\"\nmsgstr \"en attente)\"\n")
        val out = root.resolve("out")
        val written = translateTree(templates, i18n, "en", listOf("en", "fr"), out)
        assertEquals(setOf("en" to "lobby.html", "en" to "part.html", "fr" to "lobby.html", "fr" to "part.html"), written.toSet())
        assertEquals("<h3>Find Match</h3>#parse(\"en/part.html\")", out.resolve("en/lobby.html").readText())
        assertEquals("<h3>Trouver un match</h3>#parse(\"fr/part.html\")", out.resolve("fr/lobby.html").readText())
        assertEquals("<p>\$n en attente)</p>", out.resolve("fr/part.html").readText())
        root.deleteRecursively()
    }
}
