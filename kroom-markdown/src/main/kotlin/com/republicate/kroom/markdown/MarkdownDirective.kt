package com.republicate.kroom.markdown

import org.apache.velocity.VelocityContext
import org.apache.velocity.context.InternalContextAdapter
import org.apache.velocity.exception.VelocityException
import org.apache.velocity.runtime.RuntimeServices
import org.apache.velocity.runtime.directive.Directive
import org.apache.velocity.runtime.directive.DirectiveConstants
import org.apache.velocity.runtime.parser.node.Node
import org.apache.velocity.util.StringUtils
import java.io.Writer

/**
 * `#markdown(name)`, `#markdown(name, {"club": $club})` — the bridge between a developer's `#` layout and an
 * author's `%` markdown block.
 *
 * The block does NOT inherit the page's context: it sees what the page passes as the second argument, plus
 * the tools the application names in `markdown.tools` — a block is a function, called with its arguments.
 * Its output is converted to HTML. Activate with
 * `runtime.custom_directives = com.republicate.kroom.markdown.MarkdownDirective` and configure the
 * blocks' loaders under `markdown.`:
 *
 *     markdown.resource.loaders = file
 *     markdown.resource.loader.file.path = /data/content
 *
 * One [MarkdownRenderer] per host engine — hence per `%` sub-engine, per flexmark pair — cached in the
 * engine's application attributes.
 */
@Suppress("DEPRECATION")   // the classic RuntimeServices SPI IS the directive contract
class MarkdownDirective : Directive() {

    override val name: String get() = "markdown"
    override val type: Int get() = DirectiveConstants.LINE

    private lateinit var renderer: MarkdownRenderer
    private lateinit var runtime: RuntimeServices
    private var wrapper: String? = null
    private var tools: List<String> = emptyList()

    override fun init(rs: RuntimeServices, context: InternalContextAdapter?, node: Node?) {
        super.init(rs, context, node)
        runtime = rs
        wrapper = rs.getString("$PREFIX.$WRAPPER")
        tools = rs.configuration.getStringArray("$PREFIX.$TOOLS")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        renderer = synchronized(rs) {
            rs.getApplicationAttribute(RENDERER_KEY) as? MarkdownRenderer
                ?: MarkdownRenderer(rs.configuration.subset(PREFIX)?.toMap().orEmpty())
                    .also { rs.setApplicationAttribute(RENDERER_KEY, it) }
        }
    }

    override fun render(context: InternalContextAdapter, writer: Writer, node: Node): Boolean {
        val argument = node.takeIf { it.jjtGetNumChildren() > 0 }?.jjtGetChild(0)?.value(context)
            ?: throw VelocityException(
                "#markdown(): missing or null block argument at ${StringUtils.formatFileString(this)}"
            )
        val extra = node.takeIf { it.jjtGetNumChildren() > 1 }?.jjtGetChild(1)?.value(context) as? Map<*, *>
        val path = try {
            blockPath(argument.toString(), context.getCurrentTemplateName()) { context.get(it) }
        } catch (e: IllegalArgumentException) {
            throw VelocityException("#markdown(): ${e.message} at ${StringUtils.formatFileString(this)}", e)
        }
        val scope = blockScope(context, extra)
        // a draft in the context stands in for what the store holds: the editor's preview IS the page render
        val draft = (context.get(DRAFTS) as? Map<*, *>)?.get(path)?.toString()
        val html = if (draft != null) renderer.renderSource(draft, scope, path) else renderer.render(path, scope)
        wrapper?.let { decorate(it, path, html, context, writer) } ?: writer.write(html)
        return true
    }

    /**
     * What a block sees: the tools the application named (`markdown.tools`, looked up in the page's context)
     * and what the page passes — nothing else. A page's own tools (a database, a schema) stay the page's
     * without anyone having to remember to hide them; a block is handed its arguments, as a function is.
     * A null argument is left out, so the block's own `%%@` default can still apply.
     */
    private fun blockScope(page: InternalContextAdapter, arguments: Map<*, *>?): VelocityContext {
        val scope = VelocityContext()
        for (tool in tools) page.get(tool)?.let { scope.put(tool, it) }
        arguments?.forEach { (key, value) -> if (value != null) scope.put(key.toString(), value) }
        return scope
    }

    /**
     * Hand the block's HTML to a template of the application's own — where the edit affordances live. It
     * renders in the PAGE's engine and context (so `$logged`, `$authoring` and the rest are in reach) with
     * the block's `$path` and `$name` added: which of them may be edited is the wrapper's question to ask,
     * and authoring's to answer. Nothing here knows about editing.
     */
    private fun decorate(template: String, path: String, html: String, context: InternalContextAdapter, writer: Writer) {
        val scope = VelocityContext(context)
        scope.put("path", path)
        scope.put("name", path.substringAfterLast('/').substringBeforeLast('.'))
        scope.put("html", html)
        runtime.getTemplate(template).merge(scope, writer)
    }

    private companion object {
        const val PREFIX = "markdown"
        const val WRAPPER = "block.wrapper"
        /** Names of the page-context tools a block may use, comma-separated (`markdown.tools = math`). */
        const val TOOLS = "tools"
        /** Context key an editor sets: block path → the body being written, rendered instead of the stored one. */
        const val DRAFTS = "kroomDrafts"
        const val RENDERER_KEY = "com.republicate.kroom.markdown.renderer"
    }
}
