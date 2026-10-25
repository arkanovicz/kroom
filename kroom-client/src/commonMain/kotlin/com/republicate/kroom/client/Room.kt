package com.republicate.kroom.client

import com.republicate.kroom.event.Event
import com.republicate.kroom.event.JsonView
import com.republicate.kroom.event.Registry
import com.republicate.kroom.event.State
import com.republicate.kroom.event.Store
import com.republicate.kroom.event.UserPresence
import com.republicate.kson.Json
import org.apache.velocity.engine.sink.OutputSink
import org.apache.velocity.engine.VelocityContext
import org.apache.velocity.engine.VelocityEngine
import org.apache.velocity.engine.dom.HtmlDomSink
import org.apache.velocity.engine.dom.Tree

/**
 * A page on a room, the browser's side of it: every frame of the stream folded into [state] by [Store], then the
 * page merged again into the sink it was attached to, which touches only what differs. The DOM is the merge's
 * output, never read back; the page's roots are `state` (the viewer's view), `me` (its presence entry, null for a
 * spectator) and `page` (page memory, the page's own, never on the wire).
 *
 * [engine] knows the page: on the browser, the app's compiled classes behind its registry.
 */
class Room<S : State>(val name: String, val state: S, registry: Registry, private val engine: VelocityEngine) {
    val store = Store(state, registry)

    val me: UserPresence? get() = (state.json["me"] as? Json.MutableObject)?.let(::UserPresence)

    /** The `page` root: what the page knows and no room does (a timer, a match it keeps showing). [merge] after a change. */
    var page: Any? = null

    private var template: String? = null
    private var sink: OutputSink? = null

    /** The page merged under [root]; the first merge, at the snapshot, builds the tree. */
    fun <N : Any> attach(template: String, tree: Tree<N>, root: N): Room<S> = apply {
        this.template = template
        sink = HtmlDomSink(tree, root, ::key)
    }

    /** One frame of the stream: folded, then merged. The event it was, or null for `state` and an unknown name. */
    fun frame(name: String, data: String): Event? = store.fold(name, data).also { merge() }

    /** Merge the page again: a frame did, or the page memory changed. */
    fun merge() {
        val out = sink ?: return
        engine.getTemplate(template!!).merge(VelocityContext(mutableMapOf("state" to state, "me" to me, "page" to page)), out)
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
