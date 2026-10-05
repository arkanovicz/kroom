package com.republicate.kroom.markdown

import org.apache.velocity.engine.Context
import org.apache.velocity.engine.Merge
import org.apache.velocity.engine.VelocityContext
import org.apache.velocity.engine.VelocityException
import org.apache.velocity.engine.VtlMacro
import org.slf4j.LoggerFactory

/**
 * `#markdown(name)`, `#markdown(name, {"club": $club})` — the bridge between a developer's `#` page and an
 * author's `%` markdown block.
 *
 * A native velocity 3.0 macro, so the page engine may be either kind: register it with
 * `engine.addMacro("markdown", MarkdownMacro(config))` on a 3.0 `VelocityEngine` or on the classic facade
 * alike. The block renders on its own engine ([MarkdownRenderer]), whatever the page's.
 *
 * The block does NOT inherit the page's context: it sees what the page passes as the second argument, plus
 * the tools named in [MarkdownConfig.tools] — a block is a function, called with its arguments.
 *
 * A stored block that fails renders [MarkdownConfig.broken] and is logged: authored content never takes
 * a visitor's page down. A draft ([DRAFTS]) fails loudly instead — validated against the arguments this
 * call passes, then rendered — so an editor's preview shows the error and a submit can refuse the body.
 */
class MarkdownMacro(val renderer: MarkdownRenderer) : VtlMacro {

    constructor(config: MarkdownConfig = MarkdownConfig()) : this(MarkdownRenderer(config))

    private val config get() = renderer.config

    override fun render(args: List<Any?>, bodyContent: ((Appendable) -> Unit)?, context: Context, out: Appendable, scope: Merge) {
        val argument = args.getOrNull(0) ?: throw VelocityException("#markdown(): missing or null block argument")
        // the page is the template the merge started from, not the one rendering now: a region a page
        // #define's is rendered from inside its layout, and a partial's blocks are its page's too — unless
        // the context names the page (an authored page renders through one shared template)
        val page = (context[PAGE] as? String) ?: scope.templateNames.firstOrNull()?.takeUnless { it == "<undef>" }
        val path = try {
            blockPath(argument.toString(), page) { context[it] }
        } catch (e: IllegalArgumentException) {
            throw VelocityException("#markdown(): ${e.message} in ${page ?: "an unnamed template"}", e)
        }
        val arguments = blockArguments(context, args.getOrNull(1) as? Map<*, *>)
        probe(context, path, arguments)
        val block = VelocityContext(arguments)
        // a draft in the context stands in for what the store holds: the editor's preview IS the page render
        val draft = (context[DRAFTS] as? Map<*, *>)?.get(path)?.toString()
        val html = if (draft != null) {
            // the author's own text: every problem is theirs to see, including in branches this render skips
            val problems = validate(draft, arguments.keys, path)
            if (problems.isNotEmpty()) throw BlockException(path, problems)
            renderer.renderSource(draft, block, path)
        } else if (bodyContent != null && !renderer.exists(path)) {
            // #@markdown(name) … #end: the body stands in for a block nobody wrote — a site default an author
            // may override by writing the block (and trash to get the default back)
            StringBuilder().also(bodyContent).toString()
        } else try {
            renderer.render(path, block)
        } catch (e: Exception) {
            // a stored block is already published: a visitor gets the page, the operator gets the log
            log.error("#markdown(): $path failed to render", e)
            renderer.renderSource(config.broken, VelocityContext(mutableMapOf("name" to blockName(path))), "broken")
        }

        val wrapper = config.wrapper
        val interpret = scope.interpret
        if (wrapper == null || interpret == null) out.append(html)
        else interpret(wrapper, decoration(context, path, html), out, scope)
    }

    /**
     * What a block sees: the named tools, looked up in the page's context, and what the page passes —
     * nothing else. A null argument is left out, so the block's own `%%@` default can still apply.
     */
    private fun blockArguments(page: Context, arguments: Map<*, *>?): MutableMap<String, Any?> {
        val scope = mutableMapOf<String, Any?>()
        for (tool in config.tools) page[tool]?.let { scope[tool] = it }
        arguments?.forEach { (key, value) -> if (value != null) scope[key.toString()] = value }
        return scope
    }

    /** An editor asking what this block sees ([SHAPES]) gets its completion walk: plain types across the boundary. */
    @Suppress("UNCHECKED_CAST")
    private fun probe(context: Context, path: String, arguments: Map<String, Any?>) {
        val asked = context[SHAPES] as? MutableMap<String, Any?> ?: return
        if (asked.containsKey(path)) asked[path] = Shape(renderer.navigation, arguments)::unfold
    }

    /**
     * The wrapper renders in the PAGE's engine and context (so `$logged`, `$authoring` and the rest are in
     * reach) with the block's `$path`, `$name` and `$html` added: which blocks may be edited is the
     * wrapper's question to ask, and the application's to answer. Nothing here knows about editing.
     */
    private fun decoration(page: Context, path: String, html: String) = VelocityContext(parent = page).apply {
        this["path"] = path
        this["name"] = blockName(path)
        this["html"] = html
    }

    companion object {
        /** Context key an editor sets: block path → the body being written, rendered instead of the stored one. */
        const val DRAFTS = "kroomDrafts"

        /**
         * Context key an editor sets to learn what a block sees: block path → null, which the render replaces with
         * a `(steps: List<String>) -> Map<String, Any>?` walking the block's arguments one `.` at a time ([Shape]).
         */
        const val SHAPES = "kroomShapes"

        /** Context key naming the page a render is of (`pages/company/history.html`), when the merge's first template is not it. */
        const val PAGE = "kroomPage"

        private val log = LoggerFactory.getLogger(MarkdownMacro::class.java)

        /** For hosts configured by properties: the `markdown.*` ones, prefix stripped. */
        @JvmStatic
        fun fromProperties(properties: Map<String, Any?>): MarkdownMacro = MarkdownMacro(MarkdownConfig.fromProperties(properties))
    }
}

/** A draft that would break once published: what [validate] found, for the author to fix. */
class BlockException(val path: String, val problems: List<Problem>) : VelocityException(
    problems.joinToString("; ", "$path: ") { p -> p.line?.let { "line $it, column ${p.column}: ${p.message}" } ?: p.message }
)
