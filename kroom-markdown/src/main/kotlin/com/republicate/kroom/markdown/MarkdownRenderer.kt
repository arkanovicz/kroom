package com.republicate.kroom.markdown

import com.vladsch.flexmark.ext.autolink.AutolinkExtension
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet
import org.apache.velocity.engine.Context
import org.apache.velocity.engine.ResourceNotFoundException
import org.apache.velocity.engine.VelocityContext
import org.apache.velocity.engine.VelocityEngine
import org.apache.velocity.engine.jvm.ScriptingCompiler
import org.apache.velocity.engine.runtime.introspection.Acl
import org.apache.velocity.engine.runtime.introspection.Sandbox

/**
 * Renders a `%`-dialect markdown template to HTML: merge first, convert second. The order is the whole
 * point — a block is VTL that *produces* markdown, so `%foreach` may emit list items and `$name` may
 * land inside a heading, and flexmark sees a finished document rather than a template.
 *
 * Built on velocity 3.0 directly, never on the classic facade: whatever engine the application renders its
 * pages with, blocks run on this one, configured by [MarkdownConfig] alone.
 */
class MarkdownRenderer(val config: MarkdownConfig = MarkdownConfig()) {

    private val engine = markdownEngine(config)

    // flexmark's parser and renderer are immutable and thread-safe once built — built once, here.
    private val options = MutableDataSet().set(
        Parser.EXTENSIONS,
        listOf(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            AutolinkExtension.create(),
            TaskListExtension.create()
        )
    )
    private val parser: Parser = Parser.builder(options).build()
    private val html: HtmlRenderer = HtmlRenderer.builder(options).build()

    /** Render the markdown template at [path], as [MarkdownConfig.loader] serves it. */
    fun render(path: String, context: Context): String {
        val markdown = try {
            engine.mergeTemplate(path, scoped(context))
        } catch (_: ResourceNotFoundException) {
            // an unwritten block is a normal state of a live content tree, not a failure
            engine.evaluate(config.missing, scoped(context).also { it.put("name", blockName(path)) }, "missing")
        }
        return emit(markdown)
    }

    /** Render markdown the caller already holds — an editor preview, a draft never written to a loader. */
    fun renderSource(source: String, context: Context, name: String = "markdown"): String =
        emit(engine.evaluate(source, scoped(context), name))

    private fun emit(markdown: String): String = html.render(parser.parse(markdown))
}

/** A block's name, as `missing`, `broken` and the wrapper see it: `pages/club/13Ma/description.md` → `description`. */
internal fun blockName(path: String) = path.substringAfterLast('/').substringBeforeLast('.')

/**
 * The context a caller hands in comes back as it went: reads fall through to it, writes (`%set($x = 1)`)
 * stay in the child and die at the block's boundary. (Which names that context holds is `#markdown`'s
 * business: a block's arguments and the named tools.)
 */
private fun scoped(context: Context): VelocityContext = VelocityContext(parent = context)

/**
 * The one place a `%` sub-engine is built. The sandbox's parent loader is the thread-context one, the
 * application's (so ktor's dev reload still sees fresh classes). The lexer is forced last: a markdown
 * block is `%`-VTL by definition, and no configuration may say otherwise.
 */
private fun markdownEngine(config: MarkdownConfig): VelocityEngine {
    val sandbox = config.acl?.let {
        Sandbox(Acl.parse(it), Thread.currentThread().contextClassLoader ?: MarkdownRenderer::class.java.classLoader)
    }
    return VelocityEngine(
        compiler = ScriptingCompiler(sandbox = sandbox),
        loader = config.loader,
        config = config.engine.copy(lexerSource = MarkdownVtl),
    )
}
