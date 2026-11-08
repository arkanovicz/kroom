package com.republicate.kroom.event

import com.republicate.kson.Json

/**
 * A client's, and by composition a user's: online if any client is, else idle if any is, else away; lost when
 * none is left (the room's word alone, between the drop and the `leave`).
 */
enum class Presence { ONLINE, IDLE, AWAY, LOST }

/** One client of a user: a tab, an app. */
class Client(json: Json.MutableObject = Json.MutableObject()) : JsonView(json) {
    var id by string().required
    var kind by string()
    var status by enum<Presence>()
}

/**
 * A user as presence sees it: what a snapshot's `users` lists, what `join` carries (the avatar as of then: an image URL).
 * The fold keeps `clients` as of the user's first `join` (a later tab's join, an earlier one's leave are not on the wire)
 * and follows `status` alone; the snapshot's `me` is composed afresh.
 */
class UserPresence(json: Json.MutableObject = Json.MutableObject()) : JsonView(json) {
    var id by string().required
    var name by string()
    var avatar by string()
    var status by enum<Presence>()
    val clients by log(::Client)
}

/** Born by the room at a user's first connection; the handler also hears the others, with `first` false. */
class Join(json: Json.MutableObject = Json.MutableObject()) : Event("join", json = json) {
    var user by obj(::UserPresence).out
    var first by bool().out
    var count by int().out
}

/**
 * Born by the room at a user's last connection gone past the graces; the handler also hears the others, with `last`
 * false. Where the user was when that connection dropped, both read on the loop then and kept through the graces:
 * `seen`, the room's position (the SSE id of the last frame its client may have received; the graces' frames, this one
 * included, come after it), and `read`, the room's own numbering when it has one — a chat's last line (`state.last`),
 * what a store marks the user read up to; absent otherwise.
 */
class Leave(json: Json.MutableObject = Json.MutableObject()) : Event("leave", json = json) {
    var user by obj(::UserPresence).out
    var last by bool().out
    var count by int().out
    var seen by long().out
    var read by long().out
}

/** A client reports its presence; the frame carries the user's composed status and the client that changed. */
class Status(json: Json.MutableObject = Json.MutableObject()) : Event("status", json = json) {
    var status by enum<Presence>().required
    var user by string().out
    var client by obj(::Client).out
}

/** Withdraws the author's deposit of a cancellable intent, by name: the logic reads the State for it, `reduce` removes it. */
class Cancel(json: Json.MutableObject = Json.MutableObject()) : Event("cancel", json = json) {
    val intent by string().required
    var user by string().out
}
