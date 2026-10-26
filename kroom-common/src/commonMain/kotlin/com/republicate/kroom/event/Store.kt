package com.republicate.kroom.event

import com.republicate.kson.Json

/**
 * A room as a client folds it: the marking, from the `state` snapshot and every frame after,
 * through the same [State.reduce] the server runs (presence included). One stream in order, one
 * fold: a snapshot at a position equals the fold up to it.
 *
 * What the viewer holds is the app's to know: the release rule is its logic's, and every release
 * is a frame its `reduce` already sees (its own `cancel`, a firing, a `leave`). The snapshot's
 * `pending` says what was held at join, nothing more.
 */
class Store<S : State>(val state: S, val registry: Registry) {
    val json: Json.MutableObject get() = state.json

    /** One frame of the stream: the event it was, or null for `state` and for a name the registry does not know. */
    fun fold(name: String, data: Json.Object): Event? {
        if (name == "state") { json.clear(); json.putAll(data); return null }
        val frame = registry.frame(name, data as? Json.MutableObject ?: Json.MutableObject(data)) ?: return null
        state.reduce(frame)
        return frame
    }

    fun fold(name: String, data: String): Event? = fold(name, Json.parse(data) as Json.Object)
}
