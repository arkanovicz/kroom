package com.republicate.kroom.markdown

import org.apache.velocity.engine.SafeText
import org.apache.velocity.engine.VelocityContext
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** What a reference inserts is set aside: a value is shown as typed, a tool's markup (a `SafeText`) passes as the tool wrote it. */
class SlotsTest {

    /** A plugin's tool: what it answers as a `SafeText` is its own business, scripts and all. */
    class Widget {
        fun box() = SafeText(BOX)
        fun badge() = SafeText(BADGE)
        val label = "<img src=x onerror=alert(1)> *typed* | [x](y)"
        val address = "javascript:alert(1)"
        val site = "https://example.org/a?b=1&c=\"2\""
        val count = 3
        fun echo(text: String) = "($text)"
    }

    private val renderer = MarkdownRenderer()

    private fun render(source: String) = renderer.renderSource(source, VelocityContext(mutableMapOf("w" to Widget())))

    /** Nothing in [html] a browser runs: judged on the tree, as a browser reads it. */
    private fun assertInert(html: String, source: String) {
        val body = Jsoup.parseBodyFragment(html).body()
        for (element in body.allElements) {
            assertFalse(element.normalName() in RUNS, "<${element.normalName()}> from $source: $html")
            for (attribute in element.attributes()) {
                val key = attribute.key.lowercase()
                assertFalse(key.startsWith("on") || key in setOf("style", "srcdoc", "formaction"), "$key from $source: $html")
                assertFalse(Regex("""^\s*(javascript|data|vbscript):""", RegexOption.IGNORE_CASE).containsMatchIn(attribute.value), "${attribute.html()} from $source: $html")
            }
        }
    }

    @Test
    fun `a tool's markup passes as the tool wrote it, where it was called`() {
        val html = render("Before\n\$w.box()\nAfter, with \$w.badge() members.")
        assertEquals("<p>Before</p>\n$BOX\n<p>After, with $BADGE members.</p>\n", html)
    }

    @Test
    fun `a value is shown as typed, wherever it lands - text, a table, code, an attribute, an address`() {
        val typed = "&lt;img src=x onerror=alert(1)&gt; *typed* | [x](y)"
        val html = render("""
            # ${'$'}w.label

            | name | n |
            |------|---|
            | ${'$'}w.label | **${'$'}w.count** |

            `${'$'}w.label` <span title="${'$'}w.label" class="${'$'}w.label">x</span>

            [site](${'$'}w.site) [gone](${'$'}w.address) <a href="${'$'}w.address">gone too</a> [${'$'}w.label](/club/${'$'}w.count)
        """.trimIndent())
        assertContains(html, "<h1>$typed</h1>")
        assertContains(html, "<td>$typed</td>", message = "its bar is no cell's border")
        assertContains(html, "<td><strong>3</strong></td>", message = "the block's own markdown still applies around it")
        assertContains(html, "<code>$typed</code>")
        assertContains(html, """<span title="$typed" class="$typed">x</span>""", message = "in an attribute, a value that cannot leave it")
        assertContains(html, """<a href="https://example.org/a?b=1&amp;c=&quot;2&quot;">site</a>""")
        assertContains(html, "<a>gone</a>", message = "an address a value makes is judged like any other")
        assertContains(html, "<a>gone too</a>")
        assertContains(html, """<a href="/club/3">$typed</a>""")
        assertInert(html, "the values")
    }

    @Test
    fun `a value handed on is the value - to a tool, to a string, to a test`() {
        assertEquals("<p>(n3) 3 items yes</p>\n", render("\$w.echo(\"n\$w.count\") %set(\$x = \"\$w.count items\")\$x %if(\"\$w.count\" == \"3\")yes%end"))
    }

    @Test
    fun `a slot an author types is nobody's, the house's names are not an author's`() {
        val html = render("""
            <div data-kroom-slot="0000000000000000-0">mine</div>

            <div class="kroom-block note" id="kroom-admin">x</div>

            <input name="q"> <span class="kroom-edit">y</span>
        """.trimIndent() + "\n\n\$w.badge()")
        assertContains(html, "<div>mine</div>")
        assertContains(html, """<div class="note">x</div>""")
        assertContains(html, "<span>y</span>")
        assertFalse(html.contains("<input"), html)
        assertFalse(html.contains("data-kroom-slot"), html)
        assertContains(html, BADGE)
    }

    companion object {
        const val BOX = """<div class="kroom-widget" data-id="7" onclick="open()"><script>start()</script>

<p style="color:red">two</p></div>"""
        const val BADGE = """<b data-n="3">3</b>"""

        private val RUNS = setOf("script", "style", "iframe", "object", "embed", "form", "svg", "math", "base", "meta", "link", "button", "textarea", "select")
    }
}
