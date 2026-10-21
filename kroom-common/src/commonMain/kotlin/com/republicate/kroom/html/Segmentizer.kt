package com.republicate.kroom.html

/**
 * One piece of HTML text, by its range in the string it was read from. The segments of a string
 * cover it exactly, in order: concatenating them gives the string back.
 */
sealed interface Segment {
    val range: IntRange

    /** Visible text between tags (entities included, as written). */
    data class Text(override val range: IntRange) : Segment
    /** `<name` — a tag's opening, up to and including its name. */
    data class Open(val tag: String, override val range: IntRange) : Segment
    /** One attribute's value, quotes excluded; split across strings when a reference sits in it. */
    data class Attr(val tag: String, val name: String, override val range: IntRange) : Segment
    /** `>` or `/>` — the end of an opening tag. */
    data class End(val tag: String, override val range: IntRange) : Segment
    /** `</name>` whole. */
    data class Close(val tag: String, override val range: IntRange) : Segment
    /** Everything else: attribute names and quotes, whitespace in a tag, comments, declarations, raw content. */
    data class Other(override val range: IntRange) : Segment
}

/** Where the reader stands between two strings: what a reference sitting there is part of. */
sealed interface Position {
    data object Text : Position
    data class Tag(val tag: String) : Position
    data class Attr(val tag: String, val name: String, val quote: Char?, val started: Boolean = true) : Position
    data class Raw(val tag: String) : Position
    data object Comment : Position
    data object Declaration : Position
}

/**
 * The HTML reader shared by l10n and the page compiler (private/eventsv3.md §7): a state machine
 * over strings fed in document order, so a tag, an attribute value or a `<script>` opened in one
 * string and closed in a later one reads right. It knows tags, attributes, text, comments and the
 * raw content of `script`, `style`, `textarea` and `pre`; what to make of a segment is the consumer's.
 */
class Segmentizer {

    var position: Position = Position.Text
        private set

    fun segment(text: String): List<Segment> {
        val out = ArrayList<Segment>()
        var i = 0
        val n = text.length

        fun emit(segment: Segment) {
            if (segment.range.isEmpty()) return
            val last = out.lastOrNull()
            if (segment is Segment.Other && last is Segment.Other && last.range.last + 1 == segment.range.first) {
                out[out.size - 1] = Segment.Other(last.range.first..segment.range.last)
            } else out += segment
        }
        fun other(from: Int, to: Int) = emit(Segment.Other(from until to))
        fun skipSpaces(): Int { while (i < n && text[i].isWhitespace()) i++; return i }

        // Opens a quoted value when one starts at i: the quote is markup, the value follows it.
        fun quoteAt(at: Int): Char? = text[at].takeIf { it == '"' || it == '\'' }?.also { other(at, at + 1); i = at + 1 }

        // The value of an attribute from i: up to the quote, or to a space / '>' when unquoted.
        fun attrValue(tag: String, name: String, quote: Char?) {
            val start = i
            if (quote != null) {
                while (i < n && text[i] != quote) i++
                emit(Segment.Attr(tag, name, start until i))
                if (i < n) { other(i, i + 1); i++; position = Position.Tag(tag) }
                else position = Position.Attr(tag, name, quote)
            } else {
                while (i < n && !text[i].isWhitespace() && text[i] != '>') i++
                emit(Segment.Attr(tag, name, start until i))
                position = if (i < n) Position.Tag(tag) else Position.Attr(tag, name, null)
            }
        }

        while (i < n) {
            when (val p = position) {
                Position.Text -> {
                    val start = i
                    var tagAt = -1
                    while (i < n) {
                        if (text[i] == '<' && i + 1 < n && (text[i + 1].isLetter() || text[i + 1] == '/' || text[i + 1] == '!' || text[i + 1] == '?')) { tagAt = i; break }
                        i++
                    }
                    emit(Segment.Text(start until i))
                    if (tagAt < 0) break
                    when (text[i + 1]) {
                        '/' -> {
                            val nameStart = i + 2
                            var j = nameStart
                            while (j < n && isNameChar(text[j])) j++
                            val name = text.substring(nameStart, j).lowercase()
                            while (j < n && text[j] != '>') j++
                            if (j < n) j++
                            emit(Segment.Close(name, i until j))
                            i = j
                        }
                        '!', '?' -> {
                            if (text.startsWith("<!--", i)) { position = Position.Comment; other(i, i + 4); i += 4 }
                            else { position = Position.Declaration; other(i, i + 2); i += 2 }
                        }
                        else -> {
                            var j = i + 1
                            while (j < n && isNameChar(text[j])) j++
                            val name = text.substring(i + 1, j).lowercase()
                            emit(Segment.Open(name, i until j))
                            i = j
                            position = Position.Tag(name)
                        }
                    }
                }
                is Position.Tag -> {
                    val tag = p.tag
                    val start = i
                    skipSpaces()
                    while (i < n && text[i] == '/' && !(i + 1 < n && text[i + 1] == '>')) { i++; skipSpaces() }
                    other(start, i)
                    if (i >= n) break
                    if (text[i] == '>' || text.startsWith("/>", i)) {
                        val selfClosing = text[i] != '>'
                        val end = if (selfClosing) i + 2 else i + 1
                        emit(Segment.End(tag, i until end))
                        i = end
                        position = if (!selfClosing && tag in raw) Position.Raw(tag) else Position.Text
                        continue
                    }
                    val nameStart = i
                    while (i < n && !text[i].isWhitespace() && text[i] != '=' && text[i] != '>' && !text.startsWith("/>", i)) i++
                    val name = text.substring(nameStart, i).lowercase()
                    val eq = skipSpaces()
                    if (eq < n && text[eq] == '=') {
                        i++
                        skipSpaces()
                        other(nameStart, i)
                        if (i >= n) { position = Position.Attr(tag, name, null, started = false); break }   // value in the next string
                        attrValue(tag, name, quoteAt(i))
                    } else other(nameStart, i)
                }
                is Position.Attr -> attrValue(p.tag, p.name, if (p.started) p.quote else quoteAt(i))
                is Position.Raw -> {
                    val close = "</${p.tag}"
                    val at = text.indexOf(close, i, ignoreCase = true)
                    if (at < 0) { other(i, n); i = n }
                    else { other(i, at); i = at; position = Position.Text }
                }
                Position.Comment -> {
                    val at = text.indexOf("-->", i)
                    if (at < 0) { other(i, n); i = n }
                    else { other(i, at + 3); i = at + 3; position = Position.Text }
                }
                Position.Declaration -> {
                    val at = text.indexOf('>', i)
                    if (at < 0) { other(i, n); i = n }
                    else { other(i, at + 1); i = at + 1; position = Position.Text }
                }
            }
        }
        return out
    }

    private companion object {
        // Content taken verbatim: code, and what the user typed or the author aligned.
        val raw = setOf("script", "style", "textarea", "pre")
    }

    private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '-' || c == ':' || c == '_'
}
