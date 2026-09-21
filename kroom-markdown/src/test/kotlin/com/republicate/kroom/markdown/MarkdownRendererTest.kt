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
    private fun hostEngine(vararg extra: Pair<String, Any?>) = VelocityEngine().apply {
        setProperty(RuntimeConstants.INPUT_ENCODING, "UTF-8")
        setProperty(RuntimeConstants.RESOURCE_LOADERS, "classpath")
        setProperty("resource.loader.classpath.class", ClasspathResourceLoader::class.java.name)
        setProperty(RuntimeConstants.CUSTOM_DIRECTIVES, MarkdownDirective::class.java.name)
        setProperty("markdown.resource.loaders", "classpath")
        setProperty("markdown.resource.loader.classpath.class", ClasspathResourceLoader::class.java.name)
        setProperty("markdown.resource.loader.classpath.path", "content")
        extra.forEach { (key, value) -> setProperty(key, value) }
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
            host("layout.html", "view" to mapOf("path" to "/intro.md"), "club" to Club("Les Vagabonds"))
        )
    }

    @Test
    fun `a header default fills an absent root, the caller's value wins`() {
        assertEquals(
            "<main><p>ton: sobre</p>\n</main>",
            host("layout.html", "view" to mapOf("path" to "/typed.md"))
        )
        assertEquals(
            "<main><p>ton: enjoué</p>\n</main>",
            host("layout.html", "view" to mapOf("path" to "/typed.md"), "tone" to "enjoué")
        )
    }

    /**
     * A header NEED (`%%@ needed: String`, no default) the caller does not satisfy. Blocks are strict by
     * default: a positioned error, not a page quietly rendering `$needed`.
     */
    @Test
    fun `an unsatisfied header need is an error by default`() {
        assertFailsWith<MethodInvocationException> { host("layout.html", "view" to mapOf("path" to "/need.md")) }
    }

    // --- where a block lives: beside its page, under the page's own placeholders -------------------

    /**
     * A block is addressed by NAME, resolved beside the template including it, and the placeholders that
     * routed the page (`pages/club/_code_/club.html`, `$code` bound by the router) are expanded with the
     * values already in the context. The store therefore only ever sees concrete paths.
     */
    @Test
    fun `a block is resolved beside its page, placeholders expanded from the context`() {
        assertEquals(
            "<main><h2>Les Vagabonds</h2>\n<p>club 13Ma</p>\n</main>",
            host("pages/club/_code_.html", "code" to "13Ma", "club" to Club("Les Vagabonds"))
        )
    }

    @Test
    fun `a placeholder with no value in the context is an error naming it`() {
        val failure = assertFails { host("pages/club/_code_.html", "club" to Club("Les Vagabonds")) }
        assertContains(generateSequence<Throwable>(failure) { it.cause }.last().message.orEmpty(), "code")
    }

    /** An `index` page stands for its directory, so both spellings give their blocks the same folder. */
    @Test
    fun `a map argument adds to the block's scope`() {
        assertEquals(
            "<main><h2>Les Vagabonds</h2>\n<p>club 13Ma</p>\n<p>bio de Nestor</p>\n</main>",
            host("pages/club/_code_/index.html", "code" to "13Ma", "club" to Club("Les Vagabonds"), "p" to "Nestor")
        )
    }

    /** An unwritten block is a normal state of a live content tree: the page still renders. */
    @Test
    fun `a block that does not exist yet renders the placeholder`() {
        assertEquals(
            "<main><p><em>No content for <strong>description</strong>.</em></p>\n</main>",
            host("pages/club/_code_.html", "code" to "99Zz", "club" to Club("Inconnu"))
        )
    }

    /**
     * The application decorates blocks through a template of its own — where the edit affordances live.
     * It renders in the page's engine and context; this module only hands it the block.
     */
    @Test
    fun `a wrapper template receives the block's html, path and name`() {
        val engine = hostEngine("markdown.block.wrapper" to "wrapper.html")
        val html = StringWriter().also {
            engine.mergeTemplate("layout.html", "UTF-8", ctx("view" to mapOf("path" to "/intro.md"), "club" to Club("Les Vagabonds")), it)
        }.toString()
        assertEquals(
            "<main><section data-content=\"intro.md\" data-name=\"intro\"><h2>Bienvenue</h2>\n<p>chez Les Vagabonds</p>\n</section></main>",
            html
        )
    }

    /** What an editor previews is the page itself, its own text standing in for what the store holds. */
    @Test
    fun `a draft in the context is rendered instead of the stored block`() {
        assertEquals(
            "<main><h2>Les Vagabonds</h2>\n<p>brouillon</p>\n</main>",
            host(
                "pages/club/_code_.html",
                "code" to "13Ma",
                "club" to Club("Les Vagabonds"),
                "kroomDrafts" to mapOf("pages/club/13Ma/description.md" to "## \$club.name\n\nbrouillon")
            )
        )
    }

    // --- what a block sees: its arguments and the named tools, never the page -----------------------

    /**
     * A block is a function: the page's context is not its context. A page's own tools (a database, a
     * schema) stay the page's without anyone having to remember to hide them.
     */
    @Test
    fun `a block does not inherit the page's context`() {
        val failure = assertFailsWith<MethodInvocationException> { host("bare.html", "club" to Club("Les Vagabonds")) }
        assertContains(generateSequence<Throwable>(failure) { it.cause }.last().message.orEmpty(), "club")
    }

    /** The one opening an application makes on purpose: tools every block may use, named once. */
    @Test
    fun `markdown tools names what a block may use from the page`() {
        val engine = hostEngine("markdown.tools" to "club")
        val html = StringWriter().also {
            engine.mergeTemplate("bare.html", "UTF-8", ctx("club" to Club("Les Vagabonds"), "code" to "13Ma"), it)
        }.toString()
        assertEquals("<main><h2>Bienvenue</h2>\n<p>chez Les Vagabonds</p>\n</main>", html)
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
        val bare = ctx("view" to mapOf("path" to "/typed.md"))
        assertEquals("<main><p>ton: sobre</p>\n</main>", host("layout.html", bare))
        assertNull(bare.get("tone"))

        val supplied = ctx("view" to mapOf("path" to "/typed.md"), "tone" to "enjoué")
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
