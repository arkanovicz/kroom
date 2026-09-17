package com.republicate.kroom.markdown

import com.vladsch.flexmark.ext.autolink.AutolinkExtension
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet
import org.apache.velocity.VelocityContext
import org.apache.velocity.app.VelocityEngine
import org.apache.velocity.context.Context
import org.apache.velocity.runtime.RuntimeConstants
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader
import java.io.StringWriter
import java.io.Writer

/** The encoding markdown blocks are authored and served in. Not configurable: content is UTF-8. */
private const val ENCODING = "UTF-8"

/**
 * Renders a `%`-dialect markdown template to HTML: merge first, convert second. The order is the whole
 * point — a block is VTL that *produces* markdown, so `%foreach` may emit list items and `$name` may
 * land inside a heading, and flexmark sees a finished document rather than a template.
 *
 * ktor-free and engine-free at the call site: hand it a [Context] (any one — the caller's, inside
 * `#markdown`) and it answers HTML.
 *
 * [properties] are the sub-engine's Velocity properties, canonical 2.x keys, already stripped of the
 * `markdown.` prefix an application writes them under. The lexer is not among them.
 */
class MarkdownRenderer(properties: Map<String, Any?> = emptyMap()) {

    private val engine = markdownEngine(properties)

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

    /** Render the markdown template at [path], resolved by the sub-engine's loaders. */
    fun render(path: String, context: Context): String =
        StringWriter().also { render(path, context, it) }.toString()

    fun render(path: String, context: Context, out: Writer) {
        val markdown = StringWriter()
        engine.mergeTemplate(path, ENCODING, scoped(context), markdown)
        emit(markdown.toString(), out)
    }

    /** Render markdown the caller already holds — an editor preview, a draft never written to a loader. */
    fun renderSource(source: String, context: Context, name: String = "markdown"): String =
        StringWriter().also { renderSource(source, context, it, name) }.toString()

    fun renderSource(source: String, context: Context, out: Writer, name: String = "markdown") {
        val markdown = StringWriter()
        engine.evaluate(scoped(context), markdown, name, source)
        emit(markdown.toString(), out)
    }

    private fun emit(markdown: String, out: Writer) = html.render(parser.parse(markdown), out)
}

/**
 * A block gets its own scope: reads fall through to the caller's context, writes (`%set($x = 1)`) stay in
 * the child and die at the block's boundary. Chained here rather than in `#markdown` so every entry point —
 * an editor preview through `renderSource` as much as a layout through the directive — carries the same
 * guarantee.
 *
 * It scopes *bindings*, not objects: `%set($club.name = "x")` goes through the uberspector to the caller's
 * own object and mutates it. Sandboxing author content against that is the sub-engine's introspection
 * policy, not this wrapper's job.
 */
private fun scoped(context: Context): Context = VelocityContext(context)

/**
 * The one place a `%` sub-engine is built. Keep it that way: the restricted policy for author-edited
 * content (`introspector.uberspect.class` = SecureUberspector + `introspector.restrict.*`, which the
 * classic facade compiles into the 3.0 sandbox ACL) plugs in here and nowhere else.
 *
 * [properties] arrive last but one, so an application overrides the defaults; the lexer arrives last,
 * because a markdown block is `%`-VTL by definition and no property may say otherwise.
 */
private fun markdownEngine(properties: Map<String, Any?>): VelocityEngine = VelocityEngine().apply {
    setProperty(RuntimeConstants.INPUT_ENCODING, ENCODING)
    // self-sufficient default: blocks at classpath root. An app points `markdown.resource.loader.*`
    // at wherever its content actually lives.
    setProperty(RuntimeConstants.RESOURCE_LOADERS, "classpath")
    setProperty("resource.loader.classpath.class", ClasspathResourceLoader::class.java.name)
    properties.forEach { (key, value) -> setProperty(key, value) }
    setProperty(RuntimeConstants.PARSER_LEXER_CLASS, MarkdownVtl::class.java.name)
    init()
}
