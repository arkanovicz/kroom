package com.republicate.kroom.webapp.l10n

import kotlin.test.Test
import kotlin.test.assertEquals

class PoParserTest {

    private fun parse(po: String) = PoParser.parse(po.byteInputStream())

    @Test
    fun `a literal backslash before n, t or a quote is not an escape`() {
        val po = """
            msgid "path"
            msgstr "C:\\new\\tmp \\\"x\\\" line\nnext\tcol"
        """.trimIndent()
        assertEquals("C:\\new\\tmp \\\"x\\\" line\nnext\tcol", parse(po)["path"])
    }

    @Test
    fun `the header is not the translation of the empty string`() {
        val po = """
            msgid ""
            msgstr ""
            "Content-Type: text/plain; charset=UTF-8\n"

            msgid "Hello"
            msgstr "Bonjour"
        """.trimIndent()
        assertEquals(mapOf("Hello" to "Bonjour"), parse(po))
    }
}
