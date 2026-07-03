package com.republicate.kroom.webapp.l10n

import java.io.PrintWriter
import java.io.StringWriter
import java.util.NavigableMap
import java.util.TreeMap

/**
 * Translates the human-readable text inside an HTML fragment, leaving markup, scripts and styles
 * untouched. The unit of translation is a *token* — a normalized run of visible text, or the value
 * of a translatable attribute (`placeholder`/`title`/`alt`/`aria-label`); [translateToken] does the
 * actual lookup. Both the runtime AST walk and the build-time source splice feed fragments in
 * document order, so an unclosed `<script>`/`<style>` carries its "ignore" state across calls —
 * hence the per-instance [ignoring] state.
 *
 * Attribute caveat: one translatable attribute per element (the common case); a tag bearing several
 * (e.g. `title=` and `aria-label=`) only has the last one picked up.
 */
internal class HtmlFragmentTranslator(private val translateToken: (String) -> String) {

    companion object {
        private const val sep = "(?:[ \\r\\n\\t /–-]|&nbsp;|&dash;)*"
        private val textExtractor = Regex(
            """<[^>]+\s(?:placeholder|title|alt|aria-label)="(?<attr>[^"]*)"[^>]*>|(?<=>)$sep(?<text>[^<>]+?)$sep(?=<|${'$'})|(?<=>|^)$sep(?<text2>[^<>]+?)$sep(?=<)""",
            RegexOption.DOT_MATCHES_ALL
        )
        private val ignoreExp = Regex("(<script)|(</script>)|(<style)|(</style)")
    }

    private var ignoring = false

    fun translate(text: String): String {
        val ignoreMap = buildIgnoreMap(text)
        val sw = StringWriter()
        val output = PrintWriter(sw)
        var pos = 0
        while (true) {
            val match = textExtractor.find(text, pos) ?: break
            val start = match.range.first
            val end = match.range.last + 1
            if (start > pos) output.print(text.substring(pos, start))
            val ignore: Boolean = ignoreMap.floorEntry(start)?.value ?: false
            if (ignore) {
                output.print(text.substring(start, end))
            } else {
                val (_, group) = match.firstValidGroup()
                val groupStart = group.range.first
                if (groupStart > start) output.print(text.substring(start, groupStart))
                var token = unescapeHtml(group.value)
                if (containsOnlyIgnorable(token)) {
                    output.print(group.value)
                } else {
                    token = normalize(token)
                    val translated = translateToken(token)
                    // Untranslated must be byte-identical: unescape/normalize/escape is lossy
                    // (numeric entities like &#x25B6; re-escape to &amp;#…, whitespace collapses),
                    // so only re-encode when a translation actually replaced the token.
                    output.print(if (translated == token) group.value else escapeHtml(translated))
                }
                val groupEnd = group.range.last + 1
                if (groupEnd < end) output.print(text.substring(groupEnd, end))
            }
            pos = end
        }
        if (pos < text.length) output.print(text.substring(pos))
        return sw.toString()
    }

    private fun MatchResult.firstValidGroup(): Pair<Int, MatchGroup> {
        val groups = this.groups
        var i = 1
        val last = groups.size - 1
        while (i <= last && groups[i]?.range?.first == null) i++
        return i to (groups[i] ?: error("unexpected case"))
    }

    private fun normalize(str: String) = str.replace("\\s+".toRegex(), " ")

    private fun containsOnlyIgnorable(s: String) = s.all { it in "\r\n\t -;:.\"/<> 0123456789€!" }

    private fun buildIgnoreMap(text: String): NavigableMap<Int, Boolean> {
        val ret: NavigableMap<Int, Boolean> = TreeMap()
        var pos = 0
        var ignore = ignoring
        while (true) {
            val match = ignoreExp.find(text, pos) ?: break
            val start = match.range.first
            val end = match.range.last + 1
            val (groupIndex, group) = match.firstValidGroup()
            val groupStart = group.range.first
            ignore = (groupIndex % 2 != 0)
            if (ret.isEmpty() && start > 0) ret[0] = !ignore
            val groupEnd = group.range.last + 1
            ret[if (ignore) groupStart else groupEnd] = ignore
            pos = end
        }
        if (ret.isEmpty()) ret[0] = ignoring
        else ignoring = ignore
        return ret
    }

    private fun escapeHtml(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun unescapeHtml(s: String) = s
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
}
