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
 * name) refreshes when that key is written. A region is merged when a root its header declares changed (`state`, `page`:
 * a write through their fields; `me`: a snapshot), so a header declares every root the region reads, through a page
 * getter too. A snapshot, the first merge and a pass the page asks for itself merge every region and render every item.
 *
 * A region whose merge faults is the only one to suffer: the others merge, its root carries [BROKEN] (the page's
 * stylesheet says what a broken region looks like), [onFault] is told, and its next merge builds it again whole —
 * the sink starts over after a fault.
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

    /**
     * A region: its template, its sink, the roots its header declares (none: merged on every frame), how its root is
     * marked broken, whether never merged, whether its last merge faulted.
     */
    private class Region(val template: String, val sink: OutputSink, val roots: Set<String>, val mark: (Boolean) -> Unit) {
        var fresh = true
        var broken = false
    }

    /** Told of a region's merge that faulted, with its template: the operator's line. The region is marked [BROKEN] either way. */
    var onFault: (template: String, fault: Throwable) -> Unit = { template, fault -> println("kroom: region $template is broken: ${fault.stackTraceToString()}") }

    private val regions = ArrayList<Region>()
    private val reactions = ArrayList<(String, Event?) -> Unit>()

    /** A region: [template] merged under [root]; its first merge, at the snapshot, builds the tree. */
    fun <N : Any> attach(template: String, tree: Tree<N>, root: N): Room<S> = apply {
        regions += Region(template, HtmlDomSink(tree, root, ::key, ::clean), engine.getTemplate(template).header.map { it.name }.toSet()) { broken ->
            if (broken) tree.setAttribute(root, BROKEN, "") else tree.removeAttribute(root, BROKEN)
        }
    }

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

    /** An effect after the merge of each frame of [entry]'s name, handed the event typed: `on(ChifoumiEvents.round) { round -> … }`. */
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
     * Merge the regions again. A frame's pass is targeted ([all] false: a region none of whose roots changed is left alone,
     * the items the fold left untouched are clean); a pass the page asks for itself merges every region and renders every
     * item, the default — a timer writes nothing keyed, and what it shows may sit in any item ("5 min ago"). A page that
     * wrote through its fields may ask the targeted one.
     */
    fun merge(all: Boolean = true) {
        if (all) this.all = true
        for (r in regions) {
            if (!this.all && !r.fresh && r.roots.isNotEmpty() && r.roots.none(::changed)) continue
            // what the roots held made it fault: it waits for them to change, like any region
            r.fresh = false
            try {
                engine.getTemplate(r.template).merge(VelocityContext(mutableMapOf("state" to state, "me" to me, "page" to page)), r.sink)
                if (r.broken) { r.broken = false; r.mark(false) }
            } catch (fault: Throwable) {
                if (!r.broken) { r.broken = true; r.mark(true) }
                onFault(r.template, fault)
            }
        }
        this.all = false
        state.dirty!!.clear(); state.touched = false
        page?.let { it.dirty?.clear(); it.touched = false }
    }

    /** Whether a root changed since the last merge; a name the room does not know is taken as changed. */
    private fun changed(root: String): Boolean = when (root) { "state" -> state.touched; "page" -> page?.touched == true; "me" -> false; else -> true }

    companion object {
        /** The attribute a region's root carries while its last merge faulted. */
        const val BROKEN = "data-kroom-broken"
    }
}

/**
 * An item's key for the sink, what must survive a change of its content: a view's identity (its `id`, a logged event's
 * `seq`), else its [Position].
 */
fun key(value: Any?, index: Int): Any = (value as? JsonView)?.identity ?: Position(index)

/** The key of an item known by its position alone: never clean, since a removal before it shifts it. */
data class Position(val index: Int)
