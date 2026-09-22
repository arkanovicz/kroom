package com.republicate.kroom.markdown

import org.apache.velocity.engine.Context
import org.apache.velocity.engine.Merge
import org.apache.velocity.engine.VelocityContext
import org.apache.velocity.engine.VelocityException
import org.apache.velocity.engine.VtlMacro

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
 */
class MarkdownMacro(val renderer: MarkdownRenderer) : VtlMacro {

    constructor(config: MarkdownConfig = MarkdownConfig()) : this(MarkdownRenderer(config))

    private val config get() = renderer.config

    override fun render(args: List<Any?>, bodyContent: ((Appendable) -> Unit)?, context: Context, out: Appendable, scope: Merge) {
        val argument = args.getOrNull(0) ?: throw VelocityException("#markdown(): missing or null block argument")
        val page = scope.currentTemplateName.takeUnless { it == "<undef>" }
        val path = try {
            blockPath(argument.toString(), page) { context[it] }
        } catch (e: IllegalArgumentException) {
            throw VelocityException("#markdown(): ${e.message} in ${page ?: "an unnamed template"}", e)
        }
        val block = blockScope(context, args.getOrNull(1) as? Map<*, *>)
        // a draft in the context stands in for what the store holds: the editor's preview IS the page render
        val draft = (context[DRAFTS] as? Map<*, *>)?.get(path)?.toString()
        val html = if (draft != null) renderer.renderSource(draft, block, path) else renderer.render(path, block)

        val wrapper = config.wrapper
        val interpret = scope.interpret
        if (wrapper == null || interpret == null) out.append(html)
        else interpret(wrapper, decoration(context, path, html), out, scope)
    }

    /**
     * What a block sees: the named tools, looked up in the page's context, and what the page passes —
     * nothing else. A null argument is left out, so the block's own `%%@` default can still apply.
     */
    private fun blockScope(page: Context, arguments: Map<*, *>?): VelocityContext {
        val scope = VelocityContext()
        for (tool in config.tools) page[tool]?.let { scope[tool] = it }
        arguments?.forEach { (key, value) -> if (value != null) scope[key.toString()] = value }
        return scope
    }

    /**
     * The wrapper renders in the PAGE's engine and context (so `$logged`, `$authoring` and the rest are in
     * reach) with the block's `$path`, `$name` and `$html` added: which blocks may be edited is the
     * wrapper's question to ask, and the application's to answer. Nothing here knows about editing.
     */
    private fun decoration(page: Context, path: String, html: String) = VelocityContext(parent = page).apply {
        this["path"] = path
        this["name"] = path.substringAfterLast('/').substringBeforeLast('.')
        this["html"] = html
    }

    companion object {
        /** Context key an editor sets: block path → the body being written, rendered instead of the stored one. */
        const val DRAFTS = "kroomDrafts"

        /** For hosts configured by properties: the `markdown.*` ones, prefix stripped. */
        @JvmStatic
        fun fromProperties(properties: Map<String, Any?>): MarkdownMacro = MarkdownMacro(MarkdownConfig.fromProperties(properties))
    }
}
