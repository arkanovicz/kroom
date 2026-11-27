package com.republicate.kroom.velocity.markdown

import com.republicate.kroom.markdown.Markdown
import org.apache.velocity.engine.ReferenceInsertionEvents
import org.apache.velocity.engine.SafeText
import org.apache.velocity.engine.sink.PlainSyntax
import org.apache.velocity.engine.sink.TrackingSink
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.security.SecureRandom

/**
 * What one render sets aside, out of markdown's hands: everything a reference inserts. A block's own text is
 * markdown and html; what it inserts is not the author's.
 *
 * - A value is data, shown as typed. A word only this render can name stands for it in the markdown; once the
 *   block is html, the value is written where the word landed — a text, an attribute's value — so it is
 *   escaped for there, and an address it makes is judged by the sanitizer like any other.
 * - A tool's markup (a [SafeText]) passes as the tool wrote it. An empty element stands for it, and the markup
 *   takes its place once the rest is sanitized.
 */
internal class Slots {
    private val nonce = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    private val held = ArrayList<String>()
    private val values = ArrayList<String>()
    private val word = Regex("kv${nonce}x([0-9]+)z")

    /**
     * What the block is merged into — a sink the engine knows, so that the handler tells a reference written to
     * the block from one read into a string (`$tool.find("n$count")`), which is no text of the block's yet.
     */
    val out = TrackingSink(StringBuilder(), PLAIN)

    /** [value] set aside; the word that stands for it in the markdown. */
    fun keep(value: String): String {
        values += value
        return "kv${nonce}x${values.size - 1}z"
    }

    /** Each value written where its word landed under [root]: in a text, in an attribute's value — nowhere else. */
    fun write(root: Element) {
        if (values.isEmpty()) return
        root.traverse { node, _ ->
            when (node) {
                is TextNode -> if (nonce in node.wholeText) node.text(typed(node.wholeText))
                is Element -> for (attribute in node.attributes()) if (nonce in attribute.value) attribute.setValue(typed(attribute.value))
                else -> {}
            }
        }
    }

    private fun typed(text: String) = word.replace(text) { values.getOrNull(it.groupValues[1].toIntOrNull() ?: -1) ?: it.value }

    /** [markup] set aside; what stands for it in the markdown. */
    fun hold(markup: CharSequence): String {
        held += markup.toString()
        val id = "$nonce-${held.size - 1}"
        // a block element is an html block of its own, whatever line follows; anything else stays in its sentence
        return if (blockLevel(markup)) "<div $ATTRIBUTE=\"$id\"></div>\n" else "<span $ATTRIBUTE=\"$id\"></span>"
    }

    /** Whether [id] names something this render set aside — an author cannot have typed it. */
    fun holds(id: String): Boolean = index(id) != null

    /** [html] with each stand-in replaced by what it stood for. */
    fun restore(html: String): String = if (held.isEmpty()) html else STAND_IN.replace(html) { match ->
        index(match.groupValues[2])?.takeIf { match.groupValues[1] == match.groupValues[3] }?.let { held[it] } ?: match.value
    }

    private fun index(id: String): Int? =
        id.substringAfter('-', "").toIntOrNull()?.takeIf { it in held.indices && id == "$nonce-$it" }

    companion object {
        const val ATTRIBUTE = Markdown.SLOT

        /** The context key a render's slots travel under — no name a block can write. */
        const val KEY = "kroom slots"

        private val random = SecureRandom()
        private val PLAIN = PlainSyntax()
        private val STAND_IN = Regex("""<(div|span) $ATTRIBUTE="([0-9a-f]+-[0-9]+)"></(div|span)>""")
        private val FIRST_TAG = Regex("""^\s*<([A-Za-z][A-Za-z0-9]*)""")

        // commonmark's html blocks (the kind that may interrupt a paragraph)
        private val BLOCKS = setOf(
            "address", "article", "aside", "blockquote", "details", "dialog", "dd", "div", "dl", "dt", "fieldset",
            "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "iframe",
            "li", "main", "menu", "nav", "ol", "p", "section", "summary", "table", "ul"
        )

        private fun blockLevel(markup: CharSequence) =
            FIRST_TAG.find(markup)?.groupValues?.get(1)?.lowercase() in BLOCKS
    }
}

/** The block engine's insertion handler: what a reference inserts is set aside in the render's [Slots], markup or value. */
internal val SetAside = ReferenceInsertionEvents { context, _, value, site ->
    val slots = context[Slots.KEY] as? Slots
    when {
        value == null || slots == null || site.sink !== slots.out -> value
        value is SafeText -> slots.hold(value)
        else -> slots.keep(value.toString())
    }
}
