package com.republicate.kroom.webapp.l10n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PoWriterTest {

    @Test
    fun `round-trips adversarial values through PoParser`() {
        val m = mapOf(
            "Hello" to "Bonjour",
            "quote and backslash" to """He said "hi" and a \ slash""",
            // literal backslash + n (NOT a newline) — the sequential-replace trap
            "windows path" to "C:\\nope",
            "newline and tab" to "line1\nline2\ttabbed",
            "astral" to "🍪 sweet cookie",
            // empty msgstr: written faithfully, dropped by PoParser on read
            "untranslated" to ""
        )
        val roundTripped = PoParser.parse(PoWriter.write(m).byteInputStream())
        assertEquals(m.filterValues { it.isNotEmpty() }, roundTripped)
    }

    @Test
    fun `emoji survives byte-exact`() {
        val back = PoParser.parse(PoWriter.write(mapOf("cookie" to "🍪")).byteInputStream())
        assertEquals("🍪", back["cookie"])
        assertEquals(1, back["cookie"]!!.codePointCount(0, back["cookie"]!!.length))
    }

    @Test
    fun `entries are sorted by msgid`() {
        val po = PoWriter.write(mapOf("zebra" to "z", "apple" to "a", "mango" to "m"))
        val apple = po.indexOf("msgid \"apple\"")
        val mango = po.indexOf("msgid \"mango\"")
        val zebra = po.indexOf("msgid \"zebra\"")
        assertTrue(apple in 0 until mango && mango < zebra)
    }
}
