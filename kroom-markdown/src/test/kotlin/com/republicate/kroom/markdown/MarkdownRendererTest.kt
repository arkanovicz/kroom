package com.republicate.kroom.markdown

import org.apache.velocity.VelocityContext
import org.apache.velocity.app.VelocityEngine
import org.apache.velocity.runtime.RuntimeConstants
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pipeline, pinned end to end: `%`-eval then flexmark. Both halves have to be visible in the
 * expected HTML — a block is VTL that PRODUCES markdown, so a reference landing inside a heading is
 * markdown syntax by the time flexmark reads it, not after.
 */
class MarkdownRendererTest {

    data class Club(val name: String)

    class Tone(var value: String)

    private val renderer = MarkdownRenderer()

    private fun ctx(vararg pairs: Pair<String, Any?>) = VelocityContext(mutableMapOf(*pairs))

    /**
     * Space gobbling is markdown's business here, so it is pinned rather than glossed: a line ENDING on
     * a directive loses its newline, and in markdown a lost newline is a lost paragraph break — the two
     * paragraphs below fuse. Writing the directive on lines of its own (next test) is the shape an author
     * wants, and the one `parser.space_gobbling = lines` was made for.
     */
    @Test
    fun `merge then convert - directives and references feed the markdown`() {
        val source = "# Title\n\n%if(\$x)**yes**%end\n\n\$name\n"
        assertEquals(
            "<h1>Title</h1>\n<p><strong>yes</strong>\nkroom</p>\n",
            renderer.renderSource(source, ctx("x" to true, "name" to "kroom"))
        )
        assertEquals(
            "<h1>Title</h1>\n<p>kroom</p>\n",
            renderer.renderSource(source, ctx("x" to false, "name" to "kroom"))
        )
    }

    @Test
    fun `a directive on its own line leaves the document's paragraphs alone`() {
        val source = "# Title\n\n%if(\$x)\n**yes**\n%end\n\n\$name\n"
        assertEquals(
            "<h1>Title</h1>\n<p><strong>yes</strong></p>\n<p>kroom</p>\n",
            renderer.renderSource(source, ctx("x" to true, "name" to "kroom"))
        )
        assertEquals(
            "<h1>Title</h1>\n<p>kroom</p>\n",
            renderer.renderSource(source, ctx("x" to false, "name" to "kroom"))
        )
    }

    @Test
    fun `the GFM set is on - tables and strikethrough`() {
        val table = "| a | b |\n|---|---|\n| 1 | 2 |\n"
        assertEquals(
            "<table>\n<thead>\n<tr><th>a</th><th>b</th></tr>\n</thead>\n<tbody>\n<tr><td>1</td><td>2</td></tr>\n</tbody>\n</table>\n",
            renderer.renderSource(table, ctx())
        )
        assertEquals("<p><del>gone</del></p>\n", renderer.renderSource("~~gone~~\n", ctx()))
    }

    // --- end to end: a `#` layout calling `#markdown` on a `%` block -----------------------------

    /** A host engine as an application configures one: `#` layouts, blocks under `markdown.`. */
    private fun hostEngine() = VelocityEngine().apply {
        setProperty(RuntimeConstants.INPUT_ENCODING, "UTF-8")
        setProperty(RuntimeConstants.RESOURCE_LOADERS, "classpath")
        setProperty("resource.loader.classpath.class", ClasspathResourceLoader::class.java.name)
        setProperty(RuntimeConstants.CUSTOM_DIRECTIVES, MarkdownDirective::class.java.name)
        setProperty("markdown.resource.loaders", "classpath")
        setProperty("markdown.resource.loader.classpath.class", ClasspathResourceLoader::class.java.name)
        setProperty("markdown.resource.loader.classpath.path", "content")
        init()
    }

    private fun host(template: String, vararg pairs: Pair<String, Any?>): String =
        host(template, ctx(*pairs))

    /** Same, on a context the test keeps a handle on — to look at what the block left behind. */
    private fun host(template: String, context: VelocityContext): String =
        StringWriter().also { hostEngine().mergeTemplate(template, "UTF-8", context, it) }.toString()

    @Test
    fun `the layout calls the block and the block sees the caller's context`() {
        assertEquals(
            "<main><h2>Bienvenue</h2>\n<p>chez Les Vagabonds</p>\n</main>",
            host("layout.html", "view" to mapOf("path" to "intro.md"), "club" to Club("Les Vagabonds"))
        )
    }

    @Test
    fun `a header default fills an absent root, the caller's value wins`() {
        assertEquals(
            "<main><p>ton: sobre</p>\n</main>",
            host("layout.html", "view" to mapOf("path" to "typed.md"))
        )
        assertEquals(
            "<main><p>ton: enjoué</p>\n</main>",
            host("layout.html", "view" to mapOf("path" to "typed.md"), "tone" to "enjoué")
        )
    }

    /**
     * A header NEED (`%%@ needed: String`, no default) the caller does not satisfy. Pinned as OBSERVED,
     * not as wished: the interpreted pipeline does not enforce a need — the reference simply resolves to
     * nothing and renders its own source, as any undefined reference does. Validation ("every includer
     * must satisfy a block's signature") is therefore still entirely to be built; nothing under us
     * already raises.
     */
    @Test
    fun `an unsatisfied header need renders the reference literally`() {
        assertEquals(
            "<main><p>valeur: \$needed</p>\n</main>",
            host("layout.html", "view" to mapOf("path" to "need.md"))
        )
    }

    // --- scope: the block reads the caller, writes to itself --------------------------------------

    /**
     * An author's `%set` is a scratch variable, not an edit of the page the block sits in. The block sees
     * its own writes — including one shadowing a caller-supplied name — and the caller comes back exactly
     * as it went in, the shadow gone with the boundary.
     */
    @Test
    fun `a block's writes stay in the block`() {
        val caller = ctx("title" to "Les Vagabonds")
        val html = renderer.renderSource(
            "%set(\$leak = \"x\")\n%set(\$title = \"changed\")\n\$title / \$leak\n",
            caller
        )
        assertEquals("<p>changed / x</p>\n", html)
        assertEquals("Les Vagabonds", caller.get("title"))
        assertNull(caller.get("leak"))
    }

    /** The same boundary seen from the header side: a default is a binding like any other. */
    @Test
    fun `a header default fills the block's scope only, and a caller value still wins`() {
        val bare = ctx("view" to mapOf("path" to "typed.md"))
        assertEquals("<main><p>ton: sobre</p>\n</main>", host("layout.html", bare))
        assertNull(bare.get("tone"))

        val supplied = ctx("view" to mapOf("path" to "typed.md"), "tone" to "enjoué")
        assertEquals("<main><p>ton: enjoué</p>\n</main>", host("layout.html", supplied))
        assertEquals("enjoué", supplied.get("tone"))
    }

    /**
     * KNOWN LIMIT, pinned as OBSERVED. The child context scopes *bindings*; a property write is not one —
     * `%set($tone.value = ...)` resolves `$tone` through the chain, then calls a setter on the caller's own
     * object through the uberspector, which no context wrapper sees. Containing it is an introspection
     * policy (SecureUberspector / `introspector.restrict.*` on the `%` sub-engine), and if it needs a fix
     * it is upstream: nothing here should work around it.
     */
    @Test
    fun `KNOWN LIMIT - a property write pierces the scope and mutates the caller's object`() {
        val tone = Tone("sobre")
        val caller = ctx("tone" to tone)
        assertEquals(
            "<p>enjoué</p>\n",
            renderer.renderSource("%set(\$tone.value = \"enjoué\")\n\$tone.value\n", caller)
        )
        assertEquals("enjoué", tone.value)   // not "sobre" — the caller's object was mutated
    }
}
