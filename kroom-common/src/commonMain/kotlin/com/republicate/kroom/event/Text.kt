package com.republicate.kroom.event

import com.republicate.kson.Json

/**
 * A part of a text: what one writer holds at a time. Free, it is a paragraph, a list, a fenced block; held, it is
 * whatever its holder writes, blank lines and all, until the release cuts it again. Held by a client (a tab, not a
 * user: the text being typed is in one page).
 */
class Part(json: Json.MutableObject = Json.MutableObject()) : JsonView(json) {
    constructor(id: String, text: String) : this(Json.MutableObject("id" to id, "text" to text))

    val id by string().required
    var text by string().required
    var holder by string()
    var client by string()

    val free: Boolean get() = holder == null
    fun heldBy(actor: Actor): Boolean = holder == actor.id && client == actor.client

    companion object {
        /** A text cut into parts: at its blank lines, a fenced block kept whole. */
        fun cut(text: String): List<String> {
            val parts = ArrayList<String>()
            val lines = ArrayList<String>()
            var fence: String? = null
            fun close() { if (lines.isNotEmpty()) { parts += lines.joinToString("\n"); lines.clear() } }
            for (line in text.lines()) {
                val mark = FENCE.find(line)?.groupValues?.get(1)
                val open = fence
                when {
                    open != null -> {
                        lines += line
                        if (mark != null && mark[0] == open[0] && mark.length >= open.length && line.trim() == mark) fence = null
                    }
                    line.isBlank() -> close()
                    else -> { lines += line; if (mark != null) fence = mark }
                }
            }
            close()
            return parts
        }

        private val FENCE = Regex("""^ {0,3}(`{3,}|~{3,})""")
    }
}

/**
 * A text several people write, its parts in order, one writer per part. A page computes nothing: an intent names
 * parts a frame told it of and says what was done — taken, written, given back — and the fold alone decides what
 * the text is made of: the parts taken become one, the part given back is cut into its paragraphs. So a view that
 * is late is refused at the check, never applied askew, and a frame is a state, never a difference: nothing to
 * replay, a snapshot is the text. A text is never without a part: it is born with an empty one. A room with more
 * extends it.
 */
open class TextState(json: Json.MutableObject = Json.MutableObject()) : State(json) {
    val parts by seq(::Part)
    /** How many parts were ever born: the next one is `last + 1`. */
    var last by long().required
    init { if (json["last"] == null) { last = 1; parts.splice(0, 0, listOf(Part("1", ""))) } }

    /** A text as a store kept it ([text]), cut again into its parts, all free: what a room is rebuilt from. */
    constructor(text: String) : this(Part.cut(text).ifEmpty { listOf("") }.let { cut ->
        Json.MutableObject("parts" to Json.MutableArray(cut.mapIndexed { i, part -> Part((i + 1).toString(), part).json }), "last" to cut.size.toLong())
    })

    /** The text its parts make; a blank part says nothing. */
    val text: String get() = join(parts.map { it.text })

    override fun reduce(event: Event) {
        super.reduce(event)
        when (event) {
            is Take -> take(strings(event.ids).orEmpty(), event.user, event.client)
            is Write -> parts[event.id]?.let { if (it.text != event.text) it.text = event.text }
            is Release -> release(event.id)
            else -> {}
        }
    }

    /** The parts [ids], next to each other, become one — the first, saying what they all said — and it is held. */
    private fun take(ids: List<String>, holder: String?, client: String?) {
        val at = ids.firstOrNull()?.let { parts.indexOf(it) }?.takeIf { it >= 0 } ?: return
        val first = parts[at]
        if (ids.size > 1) {
            val all = join(ids.mapNotNull { parts[it]?.text })
            if (first.text != all) first.text = all
            parts.splice(at + 1, ids.size - 1)
        }
        first.holder = holder
        first.client = client
    }

    /** The part is free again, and cut: its first paragraph keeps the id, the others are born after it; left blank, it goes — the last one stays. */
    private fun release(id: String) {
        val at = parts.indexOf(id).takeIf { it >= 0 } ?: return
        val part = parts[at]
        val cut = Part.cut(part.text)
        if (cut.isEmpty() && parts.size > 1) { parts.splice(at, 1); return }
        part.holder = null
        part.client = null
        val text = cut.firstOrNull() ?: ""
        if (part.text != text) part.text = text
        if (cut.size > 1) {
            parts.splice(at + 1, 0, cut.drop(1).mapIndexed { i, more -> Part((last + 1 + i).toString(), more) })
            last += cut.size - 1
        }
    }

    private fun join(texts: List<String>) = texts.filter { it.isNotBlank() }.joinToString("\n\n")
}

/**
 * Parts asked for, to write them as one: free, next to each other, in the text's order, by a client that holds
 * nothing yet. The page waits for the frame: it is the take, folded, that gives it something to write in.
 */
class Take(json: Json.MutableObject = Json.MutableObject()) : Event("take", json = json) {
    val ids by array().required
    var user by string().out
    var client by string().out
    init {
        check { state: TextState, actor ->
            val named = strings(ids)?.takeIf { it.isNotEmpty() } ?: return@check Reason("invalid", Json.Object("field" to "ids"))
            state.parts.firstOrNull { it.heldBy(actor) }?.let { return@check Reason("holding", Json.Object("id" to it.id)) }
            val at = named.map { id ->
                val part = state.parts[id] ?: return@check Reason("gone", Json.Object("id" to id))
                if (!part.free) return@check Reason("held", Json.Object("id" to id, "by" to part.holder))
                state.parts.indexOf(id)
            }
            if (at.indices.any { it > 0 && at[it] != at[it - 1] + 1 }) Reason("scattered") else null
        }
    }
}

/** What the part its client holds says now, whole. */
class Write(json: Json.MutableObject = Json.MutableObject()) : Event("write", json = json) {
    val id by string().required
    val text by string().required
    init { check { state: TextState, actor -> held(state, actor, id) } }
}

/** The part given back. Born by the logic too, when a connection's leave is heard: what a tab held does not outlive it. */
class Release(json: Json.MutableObject = Json.MutableObject()) : Event("release", json = json) {
    constructor(id: String) : this(Json.MutableObject("id" to id))

    val id by string().required
    init { check { state: TextState, actor -> held(state, actor, id) } }
}

/** A text's alphabet: `take`, `write`, `release`, beside kroom's. A room with more extends it. */
open class TextEvents : RoomEvents() {
    val take = intent(::Take)
    val write = intent(::Write)
    val release = intent(::Release)
}

/**
 * A take is signed; what a client held is released when its leave is heard (every connection's: a tab closed
 * beside another gives back its own). The rest is the fold's. A room with more extends it.
 */
open class TextLogic<S : TextState>(events: TextEvents = TextEvents()) : Logic<S>(events) {
    init {
        on(events.take) { _, actor, take -> take.user = actor.id; take.client = actor.client; done }
        on(events.write) { _, _, _ -> done }
        on(events.release) { _, _, _ -> done }
        on(events.leave) { state, actor, _ -> Emit(state.parts.filter { it.heldBy(actor) }.map { Release(it.id) }) }
    }
}

/** The strings of an array field, null when it is absent or holds anything else. */
private fun strings(array: Json.Array?): List<String>? = array?.map { it as? String ?: return null }

private fun held(state: TextState, actor: Actor, id: String): Reason? {
    val part = state.parts[id] ?: return Reason("gone", Json.Object("id" to id))
    return if (part.heldBy(actor)) null else Reason("not_held", Json.Object("id" to id))
}
