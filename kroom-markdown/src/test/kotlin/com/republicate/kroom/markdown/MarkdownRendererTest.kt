package com.republicate.kroom.markdown

import org.apache.velocity.VelocityContext
import org.apache.velocity.engine.StrictReferenceException
import org.apache.velocity.exception.MethodInvocationException
import org.apache.velocity.app.VelocityEngine
import org.apache.velocity.runtime.RuntimeConstants
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
     * A header NEED (`%%@ needed: String`, no default) the caller does not satisfy. Blocks are strict by
     * default: a positioned error, not a page quietly rendering `$needed`.
     */
    @Test
    fun `an unsatisfied header need is an error by default`() {
        assertFailsWith<MethodInvocationException> { host("layout.html", "view" to mapOf("path" to "need.md")) }
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
     * The scope contains *bindings*, not objects: `%set($tone.value = ...)` reaches the caller's own object
     * through the uberspector. Refused by default; an application opting out gets the plain behaviour.
     */
    @Test
    fun `a property write on the caller's object is refused by default`() {
        val tone = Tone("sobre")
        renderer.renderSource("%set(\$tone.value = \"enjoué\")\n\$tone.value\n", ctx("tone" to tone))
        assertEquals("sobre", tone.value)

        restricted("introspector.restrict.writes" to "false")
            .renderSource("%set(\$tone.value = \"enjoué\")\n", ctx("tone" to tone))
        assertEquals("enjoué", tone.value)   // the pin has teeth
    }

    // --- policy: watched by default, the application's to relax through `markdown.`-prefixed properties ---

    private fun restricted(vararg properties: Pair<String, Any?>) = MarkdownRenderer(mapOf(*properties))

    /** The classic facade wraps the engine's positioned StrictReferenceException, as 2.x callers expect. */
    private fun strictFailure(block: () -> Unit): Throwable =
        assertFailsWith<MethodInvocationException> { block() }.also {
            assertTrue(generateSequence<Throwable>(it) { t -> t.cause }.any { t -> t is StrictReferenceException }, it.toString())
        }

    @Test
    fun `a block's header need is enforced by default`() {
        val strict = renderer
        val block = "%%@ needed: String\nvaleur: \$needed\n"
        val failure = strictFailure { strict.renderSource(block, ctx(), name = "need.md") }
        assertContains(failure.message.orEmpty(), "`needed: String` at need.md[line 1, column 5]")
        assertEquals("<p>valeur: ok</p>\n", strict.renderSource(block, ctx("needed" to "ok")))
    }

    @Test
    fun `a block's header type is enforced by default`() {
        val strict = renderer
        strictFailure { strict.renderSource("%%@ needed: String\n\$needed\n", ctx("needed" to 42)) }
    }

    /** The class-linkage layer: a typed Kotlin island naming a restricted class does not even link. */
    @Test
    fun `blocks are sandboxed by default`() {
        val island = "\${\"\" + java.lang.Runtime.getRuntime()}\n"
        val failure = assertFails { renderer.renderSource(island, ctx()) }
        assertTrue(generateSequence(failure) { it.cause }.any { it is NoClassDefFoundError }, failure.toString())
        // the pin has teeth: the same island links and renders once the application opts out
        val open = restricted("introspector.uberspect.class" to "org.apache.velocity.util.introspection.UberspectImpl")
        assertContains(open.renderSource(island, ctx()), "java.lang.Runtime@")
    }
}
