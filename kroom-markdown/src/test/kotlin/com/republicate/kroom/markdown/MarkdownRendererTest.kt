package com.republicate.kroom.markdown

import org.apache.velocity.engine.ResourceLoader
import org.apache.velocity.engine.ResourceNotFoundException
import org.apache.velocity.engine.SandboxViolationException
import org.apache.velocity.engine.StrictReferenceException
import org.apache.velocity.engine.VelocityContext
import org.apache.velocity.runtime.RuntimeConstants
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.apache.velocity.VelocityContext as ClassicContext
import org.apache.velocity.app.VelocityEngine as ClassicEngine
import org.apache.velocity.engine.VelocityEngine as Engine

/**
 * The pipeline, pinned end to end: `%`-eval then flexmark. Both halves have to be visible in the
 * expected HTML — a block is VTL that PRODUCES markdown, so a reference landing inside a heading is
 * markdown syntax by the time flexmark reads it, not after.
 */
class MarkdownRendererTest {

    data class Club(val name: String)

    class Tone(var value: String)

    /** Blocks from the test classpath's `content/` — a store stand-in. */
    private val content = ResourceLoader { name ->
        javaClass.classLoader.getResource("content/${name.trimStart('/')}")?.readText()
            ?: throw ResourceNotFoundException("no such block: $name")
    }

    private fun config(vararg properties: Pair<String, Any?>) =
        MarkdownConfig.fromProperties(mapOf("loader" to content, *properties))

    private val renderer = MarkdownRenderer(config())

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
        assertEquals("<h1>Title</h1>\n<p>kroom</p>\n", renderer.renderSource(source, ctx("x" to false, "name" to "kroom")))
    }

    @Test
    fun `a directive on its own line leaves the document's paragraphs alone`() {
        val source = "# Title\n\n%if(\$x)\n**yes**\n%end\n\n\$name\n"
        assertEquals(
            "<h1>Title</h1>\n<p><strong>yes</strong></p>\n<p>kroom</p>\n",
            renderer.renderSource(source, ctx("x" to true, "name" to "kroom"))
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

    /** Prose needs `$club.name` unbraced in running text — the one 2.x convenience blocks keep on purpose. */
    @Test
    fun `informal navigation is on, for prose`() {
        assertEquals("<p>chez Les Vagabonds.</p>\n", renderer.renderSource("chez \$club.name.\n", ctx("club" to Club("Les Vagabonds"))))
    }

    // --- end to end: a `#` page calling `#markdown` on a `%` block -------------------------------

    /**
     * A page engine as an application runs one — here the classic facade, the macro registered like any
     * native macro. The next section runs the same pages on a 3.0 engine.
     */
    private fun classicHost(vararg properties: Pair<String, Any?>) = ClassicEngine().apply {
        setProperty(RuntimeConstants.INPUT_ENCODING, "UTF-8")
        setProperty(RuntimeConstants.RESOURCE_LOADERS, "classpath")
        setProperty("resource.loader.classpath.class", ClasspathResourceLoader::class.java.name)
        init()
        addMacro("markdown", MarkdownMacro(config(*properties)))
    }

    private fun host(template: String, vararg pairs: Pair<String, Any?>): String =
        host(template, ClassicContext(mutableMapOf(*pairs)))

    private fun host(template: String, context: ClassicContext, engine: ClassicEngine = classicHost()): String =
        StringWriter().also { engine.mergeTemplate(template, "UTF-8", context, it) }.toString()

    @Test
    fun `the page calls the block with what it passes`() {
        assertEquals(
            "<main><h2>Bienvenue</h2>\n<p>chez Les Vagabonds</p>\n</main>",
            host("layout.html", "view" to mapOf("path" to "/intro.md"), "club" to Club("Les Vagabonds"))
        )
    }

    @Test
    fun `a header default fills an absent argument, a passed value wins`() {
        assertEquals("<main><p>ton: sobre</p>\n</main>", host("layout.html", "view" to mapOf("path" to "/typed.md")))
        assertEquals(
            "<main><p>ton: enjoué</p>\n</main>",
            host("layout.html", "view" to mapOf("path" to "/typed.md"), "tone" to "enjoué")
        )
    }

    /** Blocks are strict by default: a missing need is a positioned error, not a page quietly showing `$needed`. */
    @Test
    fun `an unsatisfied header need is an error by default`() {
        assertStrict { host("layout.html", "view" to mapOf("path" to "/need.md")) }
    }

    // --- where a block lives: beside its page, under the page's own placeholders -------------------

    /**
     * A block is addressed by NAME, resolved beside the page including it, and the placeholders that
     * routed the page (`pages/club/_code_.html`, `$code` bound by the router) are expanded with the
     * page's values. The store therefore only ever sees concrete paths.
     */
    @Test
    fun `a block is resolved beside its page, placeholders expanded from the page`() {
        assertEquals(
            "<main><h2>Les Vagabonds</h2>\n<p>club 13Ma</p>\n</main>",
            host("pages/club/_code_.html", "code" to "13Ma", "club" to Club("Les Vagabonds"))
        )
    }

    @Test
    fun `a placeholder with no value in the page is an error naming it`() {
        val failure = assertFails { host("pages/club/_code_.html", "club" to Club("Les Vagabonds")) }
        assertContains(causes(failure).joinToString { it.message.orEmpty() }, "code")
    }

    /** An `index` page stands for its directory, so both spellings give their blocks the same folder. */
    @Test
    fun `an index page gives its blocks the same folder`() {
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
        assertEquals(
            "<main><section data-content=\"intro.md\" data-name=\"intro\"><h2>Bienvenue</h2>\n<p>chez Les Vagabonds</p>\n</section></main>",
            host(
                "layout.html",
                ClassicContext(mutableMapOf("view" to mapOf("path" to "/intro.md"), "club" to Club("Les Vagabonds"))),
                classicHost("block.wrapper" to "wrapper.html")
            )
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

    // --- the page engine is the application's choice ----------------------------------------------

    /** The same macro, the same block, on a velocity 3.0 page engine: kroom imposes no facade. */
    @Test
    fun `a 3_0 page engine renders the same block`() {
        val pages = ResourceLoader { name ->
            javaClass.classLoader.getResource(name)?.readText() ?: throw ResourceNotFoundException(name)
        }
        val engine = Engine(compiler = org.apache.velocity.engine.jvm.ScriptingCompiler(), loader = pages)
        engine.addMacro("markdown", MarkdownMacro(config()))
        assertEquals(
            "<main><h2>Les Vagabonds</h2>\n<p>club 13Ma</p>\n</main>",
            engine.mergeTemplate("pages/club/_code_.html", ctx("code" to "13Ma", "club" to Club("Les Vagabonds")))
        )
    }

    // --- what a block sees: its arguments and the named tools, never the page -----------------------

    /**
     * A block is a function: the page's context is not its context. A page's own tools (a database, a
     * schema) stay the page's without anyone having to remember to hide them.
     */
    @Test
    fun `a block does not inherit the page's context`() {
        val failure = assertFails { host("bare.html", "club" to Club("Les Vagabonds")) }
        assertContains(causes(failure).joinToString { it.message.orEmpty() }, "club")
    }

    /** The one opening an application makes on purpose: tools every block may use, named once. */
    @Test
    fun `markdown tools names what a block may use from the page`() {
        assertEquals(
            "<main><h2>Bienvenue</h2>\n<p>chez Les Vagabonds</p>\n</main>",
            host("bare.html", ClassicContext(mutableMapOf("club" to Club("Les Vagabonds"))), classicHost("tools" to "club"))
        )
    }

    // --- scope: a block writes to itself -----------------------------------------------------------

    @Test
    fun `a block's writes stay in the block`() {
        val caller = ctx("title" to "Les Vagabonds")
        val html = renderer.renderSource("%set(\$leak = \"x\")\n%set(\$title = \"changed\")\n\${title} / \${leak}\n", caller)
        assertEquals("<p>changed / x</p>\n", html)
        assertEquals("Les Vagabonds", caller["title"])
        assertNull(caller["leak"])
    }

    // --- policy: watched by default, the application's to relax -------------------------------------

    /**
     * The scope contains *bindings*, not objects: `%set($tone.value = ...)` reaches the caller's own object.
     * Refused by default (`- write *`); an application choosing another ACL gets what it asked for.
     */
    @Test
    fun `a property write on the caller's object is refused by default`() {
        val tone = Tone("sobre")
        renderer.renderSource("%set(\${tone.value} = \"enjoué\")\n", ctx("tone" to tone))
        assertEquals("sobre", tone.value)

        MarkdownRenderer(config("acl" to org.apache.velocity.engine.runtime.introspection.Sandbox.DEFAULT_ACL))
            .renderSource("%set(\${tone.value} = \"enjoué\")\n", ctx("tone" to tone))
        assertEquals("enjoué", tone.value)   // the pin has teeth
    }

    @Test
    fun `a block's header need is enforced by default`() {
        val block = "%%@ needed: String\nvaleur: \$needed\n"
        val failure = assertStrict { renderer.renderSource(block, ctx(), name = "need.md") }
        assertContains(failure.message.orEmpty(), "need.md[line 1, column 5]")
        assertEquals("<p>valeur: ok</p>\n", renderer.renderSource(block, ctx("needed" to "ok")))
    }

    @Test
    fun `a block's header type is enforced by default`() {
        assertStrict { renderer.renderSource("%%@ needed: String\n\$needed\n", ctx("needed" to 42)) }
    }

    /**
     * Capability discipline: a sandboxed block derives from what it is handed and conjures nothing. Pinned
     * on the two shapes that matter — a static member, and a constructor for a class the block was never
     * given (which is how a block reached the filesystem before velocity `-20260923-01`).
     */
    @Test
    fun `blocks are sandboxed by default`() {
        val statics = "\${\"\" + java.lang.Runtime.getRuntime()}\n"
        val conjured = "\${\"\" + java.io.File(\"/etc/hostname\").exists()}\n"
        for (island in listOf(statics, conjured)) {
            val failure = assertFails { renderer.renderSource(island, ctx()) }
            assertTrue(
                causes(failure).any { it is SandboxViolationException },
                "expected a sandbox refusal for $island, got $failure"
            )
        }
        // the pin has teeth: the same islands run once the application opts out of the sandbox
        val open = MarkdownRenderer(config("sandbox" to "false"))
        assertContains(open.renderSource(statics, ctx()), "java.lang.Runtime@")
        assertContains(open.renderSource(conjured, ctx()), "true")
    }

    /**
     * The other side of that rule: what an author legitimately writes still works — literals the engine
     * owns, a loop over one, and members of the values the page handed the block.
     */
    @Test
    fun `the capability rule leaves an author's own material alone`() {
        val club = ctx("club" to Club("Les Vagabonds"))
        assertEquals("<p>1</p>\n", renderer.renderSource("%set(\$m = {\"a\": 1})\$m.a\n", ctx()))
        assertEquals("<p>3</p>\n", renderer.renderSource("%set(\$l = [1, 2, 3])\$l.size\n", ctx()))
        assertEquals("<p>1 2</p>\n", renderer.renderSource("%foreach(\$i in [1, 2])\$i %end\n", ctx()))
        assertEquals("<p>Les</p>\n", renderer.renderSource("\${club.name.substring(0, 3)}\n", club))
    }

    private fun causes(failure: Throwable) = generateSequence(failure) { it.cause }.toList()

    /** A strict failure, whichever wrapper the page engine puts around the engine's own exception. */
    private fun assertStrict(block: () -> Unit): Throwable {
        val failure = assertFails { block() }
        return causes(failure).firstOrNull { it is StrictReferenceException }
            ?: throw AssertionError("expected a StrictReferenceException, got $failure", failure)
    }
}
