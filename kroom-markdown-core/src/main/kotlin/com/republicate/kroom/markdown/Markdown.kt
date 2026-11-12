package com.republicate.kroom.markdown

import com.vladsch.flexmark.ext.autolink.AutolinkExtension
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet
import org.jsoup.nodes.Element

/**
 * Markdown to html, the GFM set on (tables, strikethrough, autolinks, task lists), and what comes out sanitized
 * ([Sanitizer]): html stays, scripting goes.
 */
object Markdown {

    /** The attribute naming a stand-in for what a caller set aside — passed only when the caller [holds] it. */
    const val SLOT = "data-kroom-slot"

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
    private val renderer: HtmlRenderer = HtmlRenderer.builder(options).build()

    /**
     * [source] rendered and sanitized. [before] sees the tree before the allowlist judges it; [holds] says whether
     * a [SLOT] value is the caller's — an author's is nobody's.
     */
    fun html(source: String, before: (Element) -> Unit = {}, holds: (String) -> Boolean = { false }): String =
        Sanitizer.clean(renderer.render(parser.parse(source)), before, holds)
}
