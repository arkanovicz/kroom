package com.republicate.kroom.markdown

import org.apache.velocity.context.InternalContextAdapter
import org.apache.velocity.exception.VelocityException
import org.apache.velocity.runtime.RuntimeServices
import org.apache.velocity.runtime.directive.Directive
import org.apache.velocity.runtime.directive.DirectiveConstants
import org.apache.velocity.runtime.parser.node.Node
import org.apache.velocity.util.StringUtils
import java.io.Writer

/**
 * `#markdown(path)` — the bridge between a developer's `#` layout and an author's `%` markdown block.
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
        val path = node.takeIf { it.jjtGetNumChildren() > 0 }?.jjtGetChild(0)?.value(context)
            ?: throw VelocityException(
                "#markdown(): missing or null path argument at ${StringUtils.formatFileString(this)}"
            )
        renderer.render(path.toString(), context, writer)
        return true
    }

    private companion object {
        const val PREFIX = "markdown"
        const val RENDERER_KEY = "com.republicate.kroom.markdown.renderer"
    }
}
