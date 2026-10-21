package com.republicate.kroom.webapp.l10n

import com.republicate.kroom.html.Segment
import com.republicate.kroom.html.Segmentizer

/**
 * Translates the human-readable text inside an HTML fragment, leaving markup, scripts and styles
 * untouched. The unit of translation is a *token* — a normalized run of visible text, or the value
 * of a translatable attribute (`placeholder`/`title`/`alt`/`aria-label`); [translateToken] does the
 * actual lookup. The HTML is read by kroom-common's [Segmentizer]: both the runtime AST walk and the
 * build-time source splice feed fragments in document order, and the reader carries an unclosed tag,
 * attribute or `<script>` across calls — hence one instance per template.
 */
internal class HtmlFragmentTranslator(private val translateToken: (String) -> String) {

    companion object {
        private val translatable = setOf("placeholder", "title", "alt", "aria-label")
        // Separators around a phrase: whitespace, slashes, dashes, the entities spelling them.
        private const val sep = "(?:[ \\r\\n\\t /–-]|&nbsp;|&dash;)*"
        private val leadingSep = Regex("^$sep")
        private val trailingSep = Regex("$sep$")
    }

    private val reader = Segmentizer()

    fun translate(text: String): String {
        val out = StringBuilder(text.length)
        for (segment in reader.segment(text)) {
            val range = segment.range
            when {
                segment is Segment.Text -> translateRun(text, range, out)
                segment is Segment.Attr && segment.name in translatable -> translateRun(text, range, out)
                else -> out.append(text, range.first, range.last + 1)
            }
        }
        return out.toString()
    }

    // A run of text: the separators around it and a leading character reference come back verbatim,
    // the phrase between them is the token. Untranslated must be byte-identical: unescape/normalize/
    // escape is lossy (numeric entities re-escape to &amp;#…, whitespace collapses), so only re-encode
    // when a translation actually replaced the token.
    private fun translateRun(text: String, range: IntRange, out: StringBuilder) {
        val run = text.substring(range.first, range.last + 1)
        val lead = leadingSep.find(run)?.value ?: ""
        val trail = if (lead.length == run.length) "" else trailingSep.find(run, lead.length)?.value ?: ""
        out.append(lead)
        val body = run.substring(lead.length, run.length - trail.length)
        val decoration = leadingDecoration.find(body)?.value ?: ""
        out.append(decoration)
        val raw = body.substring(decoration.length)
        val token = unescapeHtml(raw)
        if (token.none { it.isLetter() }) {   // no phrase without a letter: punctuation, numbers, dashes stay
            out.append(raw)
        } else {
            val normalized = normalize(token)
            val translated = translateToken(normalized)
            out.append(if (translated == normalized) raw else escapeHtml(translated))
        }
        out.append(trail)
    }

    private fun normalize(str: String) = str.replace("\\s+".toRegex(), " ")

    // A character reference — whole, or the ';' left when the velocity parser cuts one at its '#'
    // (`&#x25B6;` lexes as a macro call named x25B6, splitting the text node) — is decoration, not
    // phrase: out of the lookup key, back into the output verbatim. Leading side only; most keys
    // legitimately end on punctuation.
    private val leadingDecoration = Regex("^(?:&#(?:[0-9]+|[xX][0-9a-fA-F]+);|[;\\s])+")

    // `&` escapes unless it opens a numeric character reference (&#182; / &#x25B6;), which must
    // survive the unescape→translate→escape round-trip untouched.
    private val charRef = Regex("&(?!#(?:[0-9]+|[xX][0-9a-fA-F]+);)")

    private fun escapeHtml(s: String) = s
        .replace(charRef, "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun unescapeHtml(s: String) = s
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
}
