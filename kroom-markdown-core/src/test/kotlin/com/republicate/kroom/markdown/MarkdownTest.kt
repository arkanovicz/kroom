package com.republicate.kroom.markdown

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Markdown keeps its html and loses its scripting. */
class MarkdownTest {

    private fun render(source: String) = Markdown.html(source)

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
    fun `the GFM set is on - tables and strikethrough`() {
        val table = "| a | b |\n|---|---|\n| 1 | 2 |\n"
        assertEquals(
            "<table>\n<thead>\n<tr><th>a</th><th>b</th></tr>\n</thead>\n<tbody>\n<tr><td>1</td><td>2</td></tr>\n</tbody>\n</table>\n",
            render(table)
        )
        assertEquals("<p><del>gone</del></p>\n", render("~~gone~~\n"))
    }

    /** A caller writes on the tree before the allowlist, and a slot passes only when the caller holds it. */
    @Test
    fun `the hooks - written before the allowlist, a slot kept only when held`() {
        val source = "<div data-kroom-slot=\"mine\"></div>\n\n<div data-kroom-slot=\"yours\"></div>\n\ntext\n"
        val html = Markdown.html(source, before = { root ->
            root.selectFirst("p")!!.attr("onclick", "x()").appendElement("a").attr("href", "javascript:alert(1)").text("link")
        }, holds = { it == "mine" })
        assertEquals("<div data-kroom-slot=\"mine\"></div>\n<div></div>\n<p>text<a>link</a></p>\n", html)
        assertFalse(Markdown.html(source).contains(Markdown.SLOT), "nobody holds a slot by default")
    }

    @Test
    fun `the classics do not run`() {
        for (source in CLASSICS) assertInert(render(source), source)
    }

    companion object {
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
