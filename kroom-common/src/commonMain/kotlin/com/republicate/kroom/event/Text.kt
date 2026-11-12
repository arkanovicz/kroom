package com.republicate.kroom.event

import com.republicate.kson.Json

/**
 * A part of a text: what one writer holds at a time — a paragraph, a list, a fenced block. Held by a client
 * (a tab, not a user: the text being typed is in one page), free when by none.
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
 * A text several people write, its parts in order. One writer per part: a part's text is its holder's to say,
 * whole, so a frame is a state and never a difference — nothing to replay, a snapshot is the text. A text is
 * never without a part: it is born with an empty one. A room with more extends it.
 */
open class TextState(json: Json.MutableObject = Json.MutableObject()) : State(json) {
    val parts by seq(::Part)
    /** How many parts were ever born: the next one is `last + 1`. */
    var last by long().required
    init { if (json["last"] == null) { last = 1; parts.splice(0, 0, listOf(Part("1", ""))) } }

    /** The text its parts make; a part emptied and still held says nothing. */
    val text: String get() = parts.filter { it.text.isNotBlank() }.joinToString("\n\n") { it.text }

    override fun reduce(event: Event) {
        super.reduce(event)
        when (event) {
            is Take -> for (id in strings(event.ids).orEmpty()) parts[id]?.let { it.holder = event.user; it.client = event.client }
            is Release -> for (id in strings(event.ids).orEmpty()) {
                val part = parts[id] ?: continue
                // an emptied part goes with its holder; the last one stays
                if (part.text.isBlank() && parts.size > 1) parts.splice(parts.indexOf(id), 1)
                else { part.holder = null; part.client = null }
            }
            is Write -> write(strings(event.ids).orEmpty(), strings(event.texts).orEmpty(), strings(event.born).orEmpty())
            else -> {}
        }
    }

    /** The parts [ids] say [texts]: one for one while both last (only a text that changed is written), then the rest goes, or comes under [born]. */
    private fun write(ids: List<String>, said: List<String>, born: List<String>) {
        val texts = said.ifEmpty { listOf("") }
        val at = ids.firstOrNull()?.let { parts.indexOf(it) }?.takeIf { it >= 0 } ?: return
        val holder = parts[at].holder
        val client = parts[at].client
        val kept = minOf(ids.size, texts.size)
        for (i in 0 until kept) parts[at + i].let { if (it.text != texts[i]) it.text = texts[i] }
        val more = texts.drop(kept).mapIndexedNotNull { i, text -> born.getOrNull(i)?.let { id -> Part(id, text).also { it.holder = holder; it.client = client } } }
        if (ids.size > kept || more.isNotEmpty()) parts.splice(at + kept, ids.size - kept, more)
        if (more.isNotEmpty()) last += more.size
    }
}

/** The parts a client asks for, to write them: every one free or its own already, or none is taken. */
class Take(json: Json.MutableObject = Json.MutableObject()) : Event("take", json = json) {
    val ids by array().required
    var user by string().out
    var client by string().out
    init {
        check { state: TextState, actor ->
            val named = strings(ids)?.takeIf { it.isNotEmpty() } ?: return@check invalid("ids")
            named.firstNotNullOfOrNull { id ->
                val part = state.parts[id] ?: return@firstNotNullOfOrNull Reason("gone", Json.Object("id" to id))
                if (part.free || part.heldBy(actor)) null else Reason("held", Json.Object("id" to id, "by" to part.holder))
            }
        }
    }
}

/**
 * The parts a client gives back. Born by the logic too, when a connection's leave is heard: what a tab held
 * does not outlive it.
 */
class Release(json: Json.MutableObject = Json.MutableObject()) : Event("release", json = json) {
    constructor(ids: List<String>) : this(Json.MutableObject("ids" to Json.MutableArray(ids)))

    val ids by array().required
    init {
        check { state: TextState, actor ->
            val named = strings(ids)?.takeIf { it.isNotEmpty() } ?: return@check invalid("ids")
            named.firstOrNull { state.parts[it]?.heldBy(actor) != true }?.let { Reason("not_held", Json.Object("id" to it)) }
        }
    }
}

/**
 * What the parts a client holds say now: [ids], next to each other and in the text's order, become [texts] — the
 * i-th text is the i-th part's, a text past them is a part born there ([born], its id, the logic's), a part past
 * them goes. Typing, a paragraph cut in two, two made one, a page pasted: one event. No text at all (everything
 * selected and cut) is one empty text: the first part stays, blank and held, for what is typed next.
 */
class Write(json: Json.MutableObject = Json.MutableObject()) : Event("write", json = json) {
    val ids by array().required
    val texts by array().required
    var born by array().out
    init {
        check { state: TextState, actor ->
            val named = strings(ids)?.takeIf { it.isNotEmpty() } ?: return@check invalid("ids")
            strings(texts) ?: return@check invalid("texts")
            val at = named.map { id -> state.parts.indexOf(id).also { if (it < 0) return@check Reason("gone", Json.Object("id" to id)) } }
            named.firstOrNull { !state.parts[it]!!.heldBy(actor) }?.let { return@check Reason("not_held", Json.Object("id" to it)) }
            if (at.indices.any { it > 0 && at[it] != at[it - 1] + 1 }) Reason("scattered") else null
        }
    }
}

/** A text's alphabet: `take`, `release`, `write`, beside kroom's. A room with more extends it. */
open class TextEvents : RoomEvents() {
    val take = intent(::Take)
    val release = intent(::Release)
    val write = intent(::Write)
}

/**
 * A take is signed, a write's new parts are named, and what a client held is released when its leave is heard
 * (every connection's: a tab closed beside another gives back its own). A room with more extends it.
 */
open class TextLogic<S : TextState>(events: TextEvents = TextEvents()) : Logic<S>(events) {
    init {
        on(events.take) { _, actor, take -> take.user = actor.id; take.client = actor.client; done }
        on(events.release) { _, _, _ -> done }
        on(events.write) { state, _, write ->
            val more = strings(write.texts).orEmpty().size - strings(write.ids).orEmpty().size
            if (more > 0) write.born = Json.MutableArray((1..more).map { (state.last + it).toString() })
            done
        }
        on(events.leave) { state, actor, _ ->
            val held = state.parts.filter { it.heldBy(actor) }.map { it.id }
            if (held.isEmpty()) done else emit(Release(held))
        }
    }
}

/** The strings of an array field, null when it is absent or holds anything else. */
private fun strings(array: Json.Array?): List<String>? = array?.map { it as? String ?: return null }

private fun invalid(field: String) = Reason("invalid", Json.Object("field" to field))
