package com.republicate.kroom.html

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pinned because l10n and the page compiler both trust it to say what each byte of a template is
 * part of, including when velocity cut a tag, a value or a `<script>` across two strings.
 * A segment reads `Kind(tag.name)«its bytes»`.
 */
class SegmentizerTest {

    private fun render(text: String, s: Segment): String {
        val bytes = "«" + text.substring(s.range.first, s.range.last + 1) + "»"
        return when (s) {
            is Segment.Text -> "Text$bytes"
            is Segment.Open -> "Open(${s.tag})$bytes"
            is Segment.Attr -> "Attr(${s.tag}.${s.name})$bytes"
            is Segment.End -> "End(${s.tag})$bytes"
            is Segment.Close -> "Close(${s.tag})$bytes"
            is Segment.Other -> "Other$bytes"
        }
    }

    private fun Segmentizer.read(text: String): String =
        segment(text).joinToString(" ") { render(text, it) }

    // The segments of each string, in order, give that string back — whatever came before it.
    private fun roundTrip(vararg parts: String) {
        val reader = Segmentizer()
        for (part in parts) {
            assertEquals(part, reader.segment(part).joinToString("") { part.substring(it.range.first, it.range.last + 1) })
        }
    }

    @Test
    fun `plain text is one text segment`() {
        val html = "Hello, world & co."
        roundTrip(html)
        val r = Segmentizer()
        assertEquals("Text«Hello, world & co.»", r.read(html))
        assertEquals(Position.Text, r.position)
    }

    @Test
    fun `an element reads as open, end, text, close`() {
        val html = "<p>Hello</p>"
        roundTrip(html)
        val r = Segmentizer()
        assertEquals("Open(p)«<p» End(p)«>» Text«Hello» Close(p)«</p>»", r.read(html))
        assertEquals(Position.Text, r.position)
    }

    @Test
    fun `each valued attribute is one Attr, names and quotes are Other`() {
        val html = """<input type="text" title='Home' disabled value=x>"""
        roundTrip(html)
        val r = Segmentizer()
        assertEquals(
            """Open(input)«<input» Other« type="» Attr(input.type)«text» Other«" title='» Attr(input.title)«Home» """ +
                """Other«' disabled value=» Attr(input.value)«x» End(input)«>»""",
            r.read(html)
        )
        assertEquals(Position.Text, r.position)
        val attrs = Segmentizer().segment(html).filterIsInstance<Segment.Attr>()
        assertEquals(listOf(13..16, 26..29, 47..47), attrs.map { it.range })
    }

    @Test
    fun `a self-closing tag ends on its slash and gets back to text`() {
        val html = """<img alt="Avatar"/><br/>after"""
        roundTrip(html)
        val r = Segmentizer()
        assertEquals(
            """Open(img)«<img» Other« alt="» Attr(img.alt)«Avatar» Other«"» End(img)«/>» Open(br)«<br» End(br)«/>» Text«after»""",
            r.read(html)
        )
        assertEquals(Position.Text, r.position)
    }

    @Test
    fun `raw elements keep their content Other until their closing tag`() {
        val script = """<script>if (a <b) x = "<b>";</script>"""
        roundTrip(script)
        assertEquals(
            """Open(script)«<script» End(script)«>» Other«if (a <b) x = "<b>";» Close(script)«</script>»""",
            Segmentizer().read(script)
        )
        for (tag in listOf("style", "textarea", "pre")) {
            val html = "<$tag>a <b>bold</b> c</$tag>"
            roundTrip(html)
            assertEquals(
                "Open($tag)«<$tag» End($tag)«>» Other«a <b>bold</b> c» Close($tag)«</$tag>»",
                Segmentizer().read(html)
            )
        }
        val parts = arrayOf("""<script>var a = "<b>";""", """ x = "</b>";</script><p>""")
        roundTrip(*parts)
        val r = Segmentizer()
        assertEquals("""Open(script)«<script» End(script)«>» Other«var a = "<b>";»""", r.read(parts[0]))
        assertEquals(Position.Raw("script"), r.position)
        assertEquals("""Other« x = "</b>";» Close(script)«</script>» Open(p)«<p» End(p)«>»""", r.read(parts[1]))
        assertEquals(Position.Text, r.position)
    }

    @Test
    fun `a tag opened in one string finishes in the next`() {
        val parts = arrayOf("<a title=\"", "\">x</a>")
        roundTrip(*parts)
        val r = Segmentizer()
        assertEquals("""Open(a)«<a» Other« title="»""", r.read(parts[0]))
        assertEquals(Position.Attr("a", "title", '"'), r.position)
        // the value's rest is empty: no Attr segment, the closing quote comes first
        assertEquals("""Other«"» End(a)«>» Text«x» Close(a)«</a>»""", r.read(parts[1]))
        assertEquals(Position.Text, r.position)
    }

    @Test
    fun `an attribute value split by a reference reads as two Attr`() {
        val parts = arrayOf("<a title=\"Hello ", " world\">")
        roundTrip(*parts)
        val r = Segmentizer()
        assertEquals("""Open(a)«<a» Other« title="» Attr(a.title)«Hello »""", r.read(parts[0]))
        assertEquals(Position.Attr("a", "title", '"'), r.position)
        assertEquals("""Attr(a.title)« world» Other«"» End(a)«>»""", r.read(parts[1]))
        assertEquals(Position.Text, r.position)
    }

    @Test
    fun `a text run cut in two reads as two Text`() {
        val parts = arrayOf("<p>Hello ", " world</p>")
        roundTrip(*parts)
        val r = Segmentizer()
        assertEquals("Open(p)«<p» End(p)«>» Text«Hello »", r.read(parts[0]))
        assertEquals(Position.Text, r.position)
        assertEquals("Text« world» Close(p)«</p>»", r.read(parts[1]))
    }

    @Test
    fun `a comment is one Other and opens no tag, even across strings`() {
        val html = "<p><!-- a <b> comment -->x</p>"
        roundTrip(html)
        assertEquals(
            "Open(p)«<p» End(p)«>» Other«<!-- a <b> comment -->» Text«x» Close(p)«</p>»",
            Segmentizer().read(html)
        )
        val parts = arrayOf("<!-- a ", "<b> -->x")
        roundTrip(*parts)
        val r = Segmentizer()
        assertEquals("Other«<!-- a »", r.read(parts[0]))
        assertEquals(Position.Comment, r.position)
        assertEquals("Other«<b> -->» Text«x»", r.read(parts[1]))
        assertEquals(Position.Text, r.position)
    }

    @Test
    fun `a declaration is Other`() {
        val html = "<!DOCTYPE html><html>"
        roundTrip(html)
        assertEquals("Other«<!DOCTYPE html>» Open(html)«<html» End(html)«>»", Segmentizer().read(html))
        val parts = arrayOf("<!DOCTYPE ", "html>")
        roundTrip(*parts)
        val r = Segmentizer()
        assertEquals("Other«<!DOCTYPE »", r.read(parts[0]))
        assertEquals(Position.Declaration, r.position)
        assertEquals("Other«html>»", r.read(parts[1]))
        assertEquals(Position.Text, r.position)
    }

    @Test
    fun `a lone less-than in text is text`() {
        val html = "<p>a < b</p>"
        roundTrip(html)
        assertEquals("Open(p)«<p» End(p)«>» Text«a < b» Close(p)«</p>»", Segmentizer().read(html))
    }

    @Test
    fun `names are lowercased, ranges keep the original bytes`() {
        val html = """<P TITLE="x">y</P>"""
        roundTrip(html)
        assertEquals(
            """Open(p)«<P» Other« TITLE="» Attr(p.title)«x» Other«"» End(p)«>» Text«y» Close(p)«</P>»""",
            Segmentizer().read(html)
        )
    }

    @Test
    fun `entities stay in the text as written`() {
        val html = "<p>&amp; &#x25B6;</p>"
        roundTrip(html)
        assertEquals("Open(p)«<p» End(p)«>» Text«&amp; &#x25B6;» Close(p)«</p>»", Segmentizer().read(html))
    }

    @Test
    fun `an entity cut at its hash by the velocity lexer stays text on both sides`() {
        val parts = arrayOf("<button>&", "; Play</button>")
        roundTrip(*parts)
        val r = Segmentizer()
        assertEquals("Open(button)«<button» End(button)«>» Text«&»", r.read(parts[0]))
        assertEquals(Position.Text, r.position)
        assertEquals("Text«; Play» Close(button)«</button>»", r.read(parts[1]))
    }
}
