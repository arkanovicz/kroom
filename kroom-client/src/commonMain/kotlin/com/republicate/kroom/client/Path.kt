package com.republicate.kroom.client

import com.republicate.kson.Json

/**
 * A path into the room's JSON, not an expression: `stats.played`, `me.name`, `users.length`,
 * `@me`. Segments are keys (or indices into an array); a last `length` is a size. `@me` is the
 * viewer's id, read from the snapshot's `me` entry.
 */
class Path private constructor(val text: String, private val segments: List<String>) {
    val isMe: Boolean get() = text == ME

    /** The first key, what a shape check can see. */
    val head: String get() = segments.first()

    fun resolve(json: Json.Object?): Any? {
        if (isMe) return me(json)
        var current: Any? = json
        for ((i, seg) in segments.withIndex()) {
            current = when {
                seg == LENGTH && i == segments.lastIndex -> size(current)
                current is Json.Object -> current[seg]
                current is Json.Array -> seg.toIntOrNull()?.let { current.getOrNull(it) }
                else -> null
            }
            if (current == null) return null
        }
        return current
    }

    override fun toString() = text

    companion object {
        const val ME = "@me"
        private const val LENGTH = "length"
        private val segment = Regex("[A-Za-z_][A-Za-z0-9_]*|[0-9]+")   // a key or an index

        fun parse(text: String): Path {
            val t = text.trim()
            require(t.isNotEmpty()) { "empty path" }
            if (t == ME) return Path(t, listOf(t))
            val segs = t.split('.')
            require(segs.all { it.matches(segment) }) { "bad path: $text" }
            return Path(t, segs)
        }

        /** The viewer's id, from the snapshot. */
        fun me(json: Json.Object?): String? = json?.getObject("me")?.getString("id")

        private fun size(v: Any?): Int? = when (v) {
            is Json.Array -> v.size
            is Json.Object -> v.size
            is String -> v.length
            else -> null
        }

        /** What a `show` with no comparison asks: present, not false, not zero, not empty. */
        fun truthy(v: Any?): Boolean = when (v) {
            null -> false
            is Boolean -> v
            is Number -> v.toDouble() != 0.0
            is String -> v.isNotEmpty()
            is Json.Array -> v.isNotEmpty()
            is Json.Object -> v.isNotEmpty()
            else -> true
        }
    }
}
