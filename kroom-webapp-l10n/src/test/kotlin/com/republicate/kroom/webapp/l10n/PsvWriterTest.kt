package com.republicate.kroom.webapp.l10n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PsvWriterTest {

    @Test
    fun `round-trips adversarial values through PsvParser`() {
        val m = mapOf(
            "Hello" to "Bonjour",
            "pipe and backslash" to """a | pipe and a \ slash""",
            // literal backslash + n (NOT a newline) — the sequential-replace trap
            "windows path" to "C:\\nope",
            "newline and tab" to "line1\nline2\ttabbed",
            "astral" to "🍪 sweet cookie",
            // empty value: written faithfully, dropped by PsvParser on read
            "untranslated" to ""
        )
        val roundTripped = PsvParser.parse(PsvWriter.write(m).byteInputStream())
        assertEquals(m.filterValues { it.isNotEmpty() }, roundTripped)
    }

    @Test
    fun `emoji survives byte-exact`() {
        val back = PsvParser.parse(PsvWriter.write(mapOf("cookie" to "🍪")).byteInputStream())
        assertEquals("🍪", back["cookie"])
        assertEquals(1, back["cookie"]!!.codePointCount(0, back["cookie"]!!.length))
    }

    @Test
    fun `separator is the first unescaped pipe`() {
        // Key and value both contain pipes; only the real separator splits them.
        val back = PsvParser.parse(PsvWriter.write(mapOf("a|b" to "c|d")).byteInputStream())
        assertEquals("c|d", back["a|b"])
    }

    @Test
    fun `entries are sorted by key`() {
        val psv = PsvWriter.write(mapOf("zebra" to "z", "apple" to "a", "mango" to "m"))
        val apple = psv.indexOf("apple|")
        val mango = psv.indexOf("mango|")
        val zebra = psv.indexOf("zebra|")
        assertTrue(apple in 0 until mango && mango < zebra)
    }
}
