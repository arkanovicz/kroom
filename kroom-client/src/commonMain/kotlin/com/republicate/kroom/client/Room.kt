package com.republicate.kroom.client

import com.republicate.kroom.event.Actor
import com.republicate.kroom.event.Event
import com.republicate.kroom.event.JsonView
import com.republicate.kroom.event.Reason
import com.republicate.kroom.event.RoomEvents
import com.republicate.kroom.event.State
import com.republicate.kroom.event.Store
import com.republicate.kroom.event.UserPresence
import com.republicate.kson.Json
import org.apache.velocity.engine.VelocityContext
import org.apache.velocity.engine.VelocityEngine
import org.apache.velocity.engine.dom.HtmlDomSink
import org.apache.velocity.engine.dom.Tree
import org.apache.velocity.engine.sink.OutputSink

/**
 * A page on a room, the browser's side of it: every frame of the stream folded into [state] by [Store], then into
 * the [page] memory, then each region merged again into the sink it was attached to, which touches only what
 * differs; then the reactions. The DOM is the merges' output, never read back; a page's roots are `state` (the
 * viewer's view), `me` (its presence entry, null for a spectator) and `page`.
 *
 * A frame's merge is targeted: the fold records what it wrote ([State.dirty], the identities on each write's path),
 * and an item keyed by its identity that no write went under is clean — moved into place, its body not rendered. So
 * an item depends only on the writes under its own key: what it reads of another key (a line reading its author's
 * name) refreshes when that key is written. A snapshot, the first merge and a pass the page asks for itself render
 * every item.
 *
 * [engine] knows the pages: on the browser, the app's compiled classes behind its template registry.
 */
class Room<S : State>(val name: String, val state: S, events: RoomEvents, private val engine: VelocityEngine) {
    val store = Store(state, events)

    val me: UserPresence? get() = (state.json["me"] as? Json.MutableObject)?.let(::UserPresence)

    /** The `page` root: what the page knows and no room does. Its `reduce` sees every frame after [state]'s; after a
     *  page event (a timer) the page writes it and calls [merge]. */
    var page: State? = null
        set(value) { field = value; value?.dirty = HashSet() }

    /** Every item suspect: before the first merge, after a snapshot, for a pass the page asks whole. */
    private var all = true

    init { state.dirty = HashSet() }

    private val regions = ArrayList<Pair<String, OutputSink>>()
    private val reactions = ArrayList<(String, Event?) -> Unit>()

    /** A region: [template] merged under [root]; its first merge, at the snapshot, builds the tree. */
    fun <N : Any> attach(template: String, tree: Tree<N>, root: N): Room<S> = apply { regions += template to HtmlDomSink(tree, root, ::key, ::clean) }

    /** The sink's door: an item the fold did not write under, known by its identity (a position shifts). */
    private fun clean(key: Any?): Boolean = !all && key !is Position && key !in state.dirty!! && page?.dirty?.contains(key) != true

    /**
     * What the room would refuse first, on this view, before the POST: the declared shape, then the intent's check, by
     * the viewer from [client]. Null: post it (a spectator, or a name the room does not speak, is the server's to refuse).
     */
    fun check(type: String, payload: Json.Object, client: String? = null): Reason? {
        val me = me ?: return null
        val intent = store.events.of(type, Json.MutableObject(payload)) ?: return null
        val actor = object : Actor { override val id = me.id; override val name = me.name ?: me.id; override val client = client }
        return intent.validate() ?: intent.check(state, actor)
    }

    /** An effect after the merge of each frame of [entry]'s name, handed the event typed: `on(LobbyEvents.round) { round -> … }`. */
    @Suppress("UNCHECKED_CAST")
    fun <E : Event> on(entry: RoomEvents.Entry<E>, reaction: (E) -> Unit): Room<S> =
        react { name, event -> if (name == entry.name && event != null) reaction(event as E) }   // folded by the entry's factory: an E

    /** An effect after each frame's merge, with the frame's name and the event it was (null for `state`, or a name the room does not speak). */
    fun react(reaction: (name: String, event: Event?) -> Unit): Room<S> = apply { reactions += reaction }

    /** One frame of the stream: folded, merged, reacted to. */
    fun frame(name: String, data: String): Event? {
        val event = store.fold(name, data)
        if (name == "state") all = true   // a snapshot: nothing recorded
        event?.let { page?.reduce(it) }
        merge(all = false)
        reactions.forEach { it(name, event) }
        return event
    }

    /**
     * Merge every region again. A frame's pass is targeted ([all] false: the items its fold left untouched are clean); a pass
     * the page asks for itself renders every item, the default — a timer writes nothing keyed, and what it shows may sit
     * in any item ("5 min ago"). A page that wrote through its fields may ask the targeted one.
     */
    fun merge(all: Boolean = true) {
        if (all) this.all = true
        for ((template, sink) in regions) engine.getTemplate(template).merge(VelocityContext(mutableMapOf("state" to state, "me" to me, "page" to page)), sink)
        this.all = false
        state.dirty!!.clear()
        page?.dirty?.clear()
    }
}

/**
 * An item's key for the sink, what must survive a change of its content: a view's identity (its `id`, a logged event's
 * `seq`), else its [Position].
 */
fun key(value: Any?, index: Int): Any = (value as? JsonView)?.identity ?: Position(index)

/** The key of an item known by its position alone: never clean, since a removal before it shifts it. */
data class Position(val index: Int)
