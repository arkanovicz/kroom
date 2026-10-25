package com.republicate.kroom.event

import com.republicate.kson.Json

/**
 * A room as a client folds it: the marking, from the `state` snapshot and every frame after,
 * through the same [State.reduce] the server runs, plus `users`, the presence the room adds to
 * the snapshot and this fold keeps from the presence frames. One stream in order, one fold: a
 * snapshot at a position equals the fold up to it.
 *
 * What the viewer holds is the app's to know: the release rule is its logic's, and every release
 * is a frame its `reduce` already sees (its own `cancel`, a firing, a `leave`). The snapshot's
 * `pending` says what was held at join, nothing more.
 */
class Store<S : State>(val state: S, val registry: Registry) {
    val json: Json.MutableObject get() = state.json

    val users: List<UserPresence> get() = state.users

    /** One frame of the stream: the event it was, or null for `state` and for a name the registry does not know. */
    fun fold(name: String, data: Json.Object): Event? {
        if (name == "state") { json.clear(); json.putAll(data); return null }
        val frame = registry.frame(name, data as? Json.MutableObject ?: Json.MutableObject(data)) ?: return null
        when (frame) {
            is Join -> frame.user?.let { u -> array("users").let { users -> users.indexOfFirst { (it as Json.Object).getString("id") == u.id }.let { i -> if (i < 0) users.add(u.json) else users[i] = u.json } } }
            is Leave -> frame.user?.let { u -> array("users").removeAll { (it as Json.Object).getString("id") == u.id } }
            is Status -> array("users").firstOrNull { (it as Json.Object).getString("id") == frame.user }?.let { (it as Json.MutableObject)["status"] = frame.status.name }
            else -> {}
        }
        state.reduce(frame)
        return frame
    }

    fun fold(name: String, data: String): Event? = fold(name, Json.parse(data) as Json.Object)

    private fun array(key: String): Json.MutableArray = json[key] as? Json.MutableArray ?: Json.MutableArray().also { json[key] = it }
}
