package com.republicate.kroom.markdown

import org.apache.velocity.engine.ReferenceInsertionEvents
import org.apache.velocity.engine.SafeText
import java.security.SecureRandom

/**
 * What one render sets aside: the markup a tool answered (a [SafeText]), kept out of markdown's hands and the
 * sanitizer's. Where it was inserted stands an empty element only this render can name; once the block is
 * html and sanitized, the markup takes its place, as the tool wrote it.
 */
internal class Slots {
    private val nonce = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    private val held = ArrayList<String>()

    val isEmpty: Boolean get() = held.isEmpty()

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
        const val ATTRIBUTE = "data-kroom-slot"

        /** The context key a render's slots travel under — no name a block can write. */
        const val KEY = "kroom slots"

        private val random = SecureRandom()
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

/** The block engine's insertion handler: markup is set aside in the render's [Slots], a value goes on as it is. */
internal val SetAside = ReferenceInsertionEvents { context, _, value, _ ->
    if (value is SafeText) (context[Slots.KEY] as? Slots)?.hold(value) ?: value else value
}
