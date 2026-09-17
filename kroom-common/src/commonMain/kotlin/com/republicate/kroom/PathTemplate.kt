package com.republicate.kroom

/**
 * A resource path carrying placeholder segments — `pages/club/_code_/club.html`, `pages/club/_code_.md` —
 * and the two directions the content tree needs it in: a URL matched against it yields its values
 * (`/club/13Ma` → `code = 13Ma`), and those values expand it back into a concrete path the stores read
 * and write. One rule, one place: page routing and block inclusion cannot drift apart.
 *
 * A placeholder is `_name_`, with `name` alphanumeric and starting with a letter — enough to keep
 * `my_file.md` a plain name. It matches within one segment, never across `/`.
 */
class PathTemplate(val template: String) {

    private val segments = template.split('/')

    /** Placeholder names, in path order; empty for a concrete path. */
    val params: List<String> = segments.flatMap { segment -> TOKEN.findAll(segment).map { it.groupValues[1] }.toList() }

    val isConcrete: Boolean get() = params.isEmpty()

    /** The same path with `_name_` written `{name}` — the ktor route spelling. */
    val pattern: String = segments.joinToString("/") { TOKEN.replace(it) { m -> "{${m.groupValues[1]}}" } }

    /** Substitute every placeholder; [values] answering null for a declared one is an error, never a guess. */
    fun expand(values: (String) -> Any?): String = segments.joinToString("/") { segment ->
        TOKEN.replace(segment) { m ->
            val name = m.groupValues[1]
            val value = values(name)?.toString()
                ?: throw IllegalArgumentException("no value for '$name' in path template '$template'")
            require('/' !in value) { "value for '$name' spans a path segment: '$value'" }
            value
        }
    }

    fun expand(values: Map<String, Any?>): String = expand { values[it] }

    /** The placeholder values [path] gives this template, or null when it does not match. */
    fun match(path: String): Map<String, String>? {
        val parts = path.split('/')
        if (parts.size != segments.size) return null
        val out = LinkedHashMap<String, String>(params.size)
        for ((segment, part) in segments.zip(parts)) {
            if (TOKEN.containsMatchIn(segment)) {
                val values = segmentRegex(segment).matchEntire(part)?.groupValues ?: return null
                TOKEN.findAll(segment).forEachIndexed { i, m -> out[m.groupValues[1]] = values[i + 1] }
            } else if (segment != part) return null
        }
        return out
    }

    override fun toString(): String = template

    private companion object {
        val TOKEN = Regex("_([A-Za-z][A-Za-z0-9]*)_")
        fun segmentRegex(segment: String): Regex {
            val sb = StringBuilder()
            var last = 0
            for (m in TOKEN.findAll(segment)) {
                sb.append(Regex.escape(segment.substring(last, m.range.first))).append("([^/]+?)")
                last = m.range.last + 1
            }
            sb.append(Regex.escape(segment.substring(last)))
            return Regex(sb.toString())
        }
    }
}
