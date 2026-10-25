package com.republicate.kroom.client

import com.republicate.kroom.event.Event
import com.republicate.kroom.event.JsonView
import com.republicate.kroom.event.Registry
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
 * [engine] knows the pages: on the browser, the app's compiled classes behind its registry.
 */
class Room<S : State>(val name: String, val state: S, registry: Registry, private val engine: VelocityEngine) {
    val store = Store(state, registry)

    val me: UserPresence? get() = (state.json["me"] as? Json.MutableObject)?.let(::UserPresence)

    /** The `page` root: what the page knows and no room does. Its `reduce` sees every frame after [state]'s; after a
     *  page event (a timer) the page writes it and calls [merge]. */
    var page: State? = null

    private val regions = ArrayList<Pair<String, OutputSink>>()
    private val reactions = ArrayList<(String, Event?) -> Unit>()

    /** A region: [template] merged under [root]; its first merge, at the snapshot, builds the tree. */
    fun <N : Any> attach(template: String, tree: Tree<N>, root: N): Room<S> = apply { regions += template to HtmlDomSink(tree, root, ::key) }

    /** An effect after each frame's merge, with the frame's name and the event it was (null for `state`, or a name the registry does not know). */
    fun react(reaction: (name: String, event: Event?) -> Unit): Room<S> = apply { reactions += reaction }

    /** One frame of the stream: folded, merged, reacted to. */
    fun frame(name: String, data: String): Event? {
        val event = store.fold(name, data)
        event?.let { page?.reduce(it) }
        merge()
        reactions.forEach { it(name, event) }
        return event
    }

    /** Merge every region again: a frame did, or the page memory changed. */
    fun merge() {
        for ((template, sink) in regions) engine.getTemplate(template).merge(VelocityContext(mutableMapOf("state" to state, "me" to me, "page" to page)), sink)
    }
}

/**
 * An item's identity, what must survive a change of its content, from its declarations: a view's `id`, a logged
 * event's `seq`, else its position.
 */
fun key(value: Any?, index: Int): Any? {
    val view = value as? JsonView ?: return index
    for (name in KEYS) if (view.fields.any { it.name == name }) view.json[name]?.let { return it }
    return index
}

private val KEYS = listOf("id", "seq")
