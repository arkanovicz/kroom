package com.republicate.kroom.markdown

import org.apache.velocity.engine.SafeText
import org.apache.velocity.engine.VelocityContext
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A block keeps its html and loses its scripting; a tool's markup, a `SafeText`, passes as the tool wrote it. */
class SanitizerTest {

    /** A plugin's tool: what it answers as a `SafeText` is its own business, scripts and all. */
    class Widget {
        fun box() = SafeText(BOX)
        fun badge() = SafeText(BADGE)
        val label = "<img src=x onerror=alert(1)> <em>typed</em>"
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
    fun `a block keeps its html and loses its scripting`() {
        val html = render("""
            Hello <b class="x">bold</b> <script>alert(1)</script><img src=x onerror=alert(2)>

            <div class="grid" id="top" style="position:fixed" onclick="x()" data-api="/evil">in</div>

            [gone](javascript:alert(3)) [here](/there#top) [mail](mailto:a@b.c) [call](tel:+33123456789) [out](https://example.org/)
        """.trimIndent())
        assertContains(html, """<b class="x">bold</b>""")
        assertContains(html, """<img src="x">""")
        assertContains(html, """<div class="grid" id="top">in</div>""")
        assertContains(html, """<a href="/there#top">here</a>""", message = "a relative address stays as typed")
        assertContains(html, """<a href="mailto:a@b.c">mail</a>""")
        assertContains(html, """<a href="tel:+33123456789">call</a>""")
        assertContains(html, """<a href="https://example.org/">out</a>""")
        assertFalse(html.contains("alert"), html)
        assertInert(html, "the block")
    }

    @Test
    fun `what markdown renders survives - tables, task lists, code, struck text, bare links`() {
        val html = render("""
            | a | b |
            |:-:|--:|
            | 1 | 2 |

            - [x] done
            - [ ] to do

            ```kotlin
            val x = "<b>"
            ```

            ~~old~~ https://example.org/page
        """.trimIndent())
        assertContains(html, """<th align="center">a</th>""")
        assertContains(html, """<td align="right">2</td>""")
        assertTrue(Regex("""<input type="checkbox"[^>]*checked[^>]*>""").containsMatchIn(html), html)
        assertContains(html, """<code class="language-kotlin">val x = "&lt;b&gt;"""")
        assertContains(html, "<del>old</del>")
        assertContains(html, """<a href="https://example.org/page">https://example.org/page</a>""")
    }

    @Test
    fun `a tool's markup passes as the tool wrote it, where it was called`() {
        val html = render("Before\n\$w.box()\nAfter, with \$w.badge() members.")
        assertEquals("<p>Before</p>\n$BOX\n<p>After, with $BADGE members.</p>\n", html)
    }

    @Test
    fun `a value that is no SafeText is sanitized like the author's own text`() {
        val html = render("- \$w.label")
        assertContains(html, """<img src="x"> <em>typed</em>""")
        assertInert(html, "the label")
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

    @Test
    fun `the classics do not run`() {
        for (source in CLASSICS) assertInert(render(source), source)
    }

    companion object {
        const val BOX = """<div class="kroom-widget" data-id="7" onclick="open()"><script>start()</script>

<p style="color:red">two</p></div>"""
        const val BADGE = """<b data-n="3">3</b>"""

        private val RUNS = setOf("script", "style", "iframe", "object", "embed", "form", "svg", "math", "base", "meta", "link", "button", "textarea", "select")

        private val CLASSICS = listOf(
            """<svg onload=alert(1)><circle r=1 /></svg>""",
            """<math><mtext><table><mglyph><style><!--</style><img title="--&gt;&lt;img src=1 onerror=alert(1)&gt;">""",
            """<a href="jav&#x09;ascript:alert(1)">x</a>""",
            """<a href=" javascript:alert(1)">x</a>""",
            """<a href="JaVaScRiPt:alert(1)">x</a>""",
            """<img src="data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==">""",
            """<iframe srcdoc="<script>alert(1)</script>"></iframe>""",
            """<form action="https://evil.example"><input name="password"><button formaction="javascript:alert(1)">go</button></form>""",
            """<details open ontoggle=alert(1)>x</details>""",
            """<noscript><p title="</noscript><img src=x onerror=alert(1)>">""",
            """<a href="//evil.example" onmouseover=alert(1)>x</a>""",
            """<base href="https://evil.example/"><meta http-equiv="refresh" content="0;url=javascript:alert(1)"><link rel="stylesheet" href="//evil.example/x.css">""",
            """<img src=x onerror
            =alert(1)>""",
            """<video src=x onerror=alert(1)><source onerror=alert(2)></video>""",
            """<object data="javascript:alert(1)"></object><embed src="javascript:alert(1)">""",
            """<p style="background:url(javascript:alert(1))">x</p>""",
            """[x](javascript:alert(1)) ![y](javascript:alert(2)) <javascript:alert(3)>""",
        )
    }
}
