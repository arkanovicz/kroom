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
 * `#markdown(name)`, `#markdown(name, {"person": $p})` — the bridge between a developer's `#` layout and an author's `%` markdown block.
 *
 * The block is merged against a child of the **caller's** context (so `$club.name` means in the block what
 * it means in the layout, while the block's own `%set` dies at the boundary — see [MarkdownRenderer]) and
 * its output converted to HTML. Activate with
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

    override fun init(rs: RuntimeServices, context: InternalContextAdapter?, node: Node?) {
        super.init(rs, context, node)
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
        val scope = extra?.let { args -> VelocityContext(context).also { c -> args.forEach { (k, v) -> c.put(k.toString(), v) } } }
        renderer.render(path, scope ?: context, writer)
        return true
    }

    private companion object {
        const val PREFIX = "markdown"
        const val RENDERER_KEY = "com.republicate.kroom.markdown.renderer"
    }
}
