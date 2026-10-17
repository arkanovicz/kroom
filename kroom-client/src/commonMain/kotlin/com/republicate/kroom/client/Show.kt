package com.republicate.kroom.client

import com.republicate.kson.Json

/**
 * The `data-k-show` grammar, five forms and a negation:
 * `p` (truthy), `p=lit`, `p!=lit`, `p∋lit` (an array holding it, an object keyed by it, a string
 * containing it), any of them behind `!`. A literal is text, compared to the value's text, or `@me`.
 */
class Show private constructor(val text: String, val negated: Boolean, val path: Path, val op: Op, val literal: String?) {
    enum class Op(val token: String) { TRUTHY(""), EQ("="), NE("!="), HAS("∋") }

    fun test(json: Json.Object?): Boolean {
        val value = path.resolve(json)
        val lit = literal?.let { if (it == Path.ME) Path.me(json) else it }
        val r = when (op) {
            Op.TRUTHY -> Path.truthy(value)
            Op.EQ -> lit != null && same(value, lit)
            Op.NE -> !(lit != null && same(value, lit))
            Op.HAS -> lit != null && has(value, lit)
        }
        return r != negated
    }

    override fun toString() = text

    companion object {
        fun parse(text: String): Show {
            var t = text.trim()
            val negated = t.startsWith("!") && !t.startsWith("!=")
            if (negated) t = t.substring(1).trim()
            // the longest token first: `!=` before `=`
            for (op in listOf(Op.NE, Op.HAS, Op.EQ)) {
                val i = t.indexOf(op.token)
                if (i > 0) return Show(text, negated, Path.parse(t.substring(0, i)), op, t.substring(i + op.token.length).trim().also { require(it.isNotEmpty()) { "bad show: $text" } })
            }
            return Show(text, negated, Path.parse(t), Op.TRUTHY, null)
        }

        private fun same(value: Any?, lit: String) = value != null && value !is Json && value.toString() == lit

        private fun has(value: Any?, lit: String) = when (value) {
            is Json.Array -> value.any { it != null && it !is Json && it.toString() == lit }
            is Json.Object -> value.containsKey(lit)
            is String -> value.contains(lit)
            else -> false
        }
    }
}
