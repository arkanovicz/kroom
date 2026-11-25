package com.republicate.kroom.event

import com.republicate.kson.Json

/** A room as its lobby shows it: who is in. An app's subclass adds what its rooms show (who is in a call). */
open class Roster(json: Json.MutableObject = Json.MutableObject()) : JsonView(json) {
    var id by string().required
    val users by keyed(::UserPresence)
}

/** An event about one room of a lobby. The lobby drops it when that room does not show. */
abstract class RosterEvent(name: String, json: Json.MutableObject = Json.MutableObject()) : Event(name, json = json) {
    var room by string().required.out
}

/** A room shows: its roster whole, new or restated. */
class Show<R : Roster>(roster: (Json.MutableObject) -> R, json: Json.MutableObject = Json.MutableObject()) : Event("show", json = json) {
    var roster by obj(roster).out
}

/** A room no longer shows: it closed, or it chose to hide. */
class Hide(json: Json.MutableObject = Json.MutableObject()) : RosterEvent("hide", json)

/** The room's `join`: a user's first connection there. */
class Enter(json: Json.MutableObject = Json.MutableObject()) : RosterEvent("enter", json) {
    var user by obj(::UserPresence).out
}

/** The room's `status`: the user's composed presence there. */
class Present(json: Json.MutableObject = Json.MutableObject()) : RosterEvent("present", json) {
    var user by string().required.out
    var status by enum<Presence>().required.out
}

/** The room's `leave`: a user's last connection there, gone past the graces. */
class Exit(json: Json.MutableObject = Json.MutableObject()) : RosterEvent("exit", json) {
    var user by string().required.out
}

/**
 * A lobby's data: the rooms of its group that show, each a [roster] (`::Roster`, or the app's subclass).
 * The base's `users` is who watches the lobby. An app's State extends it and reduces its own [RosterEvent]s.
 */
open class LobbyState<R : Roster>(json: Json.MutableObject = Json.MutableObject(), val roster: (Json.MutableObject) -> R) : State(json) {
    val rooms by keyed(roster)

    override fun reduce(event: Event) {
        super.reduce(event)
        when (event) {
            is Show<*> -> event.roster?.let { rooms.add(roster(it.json)) }
            is Hide -> rooms.remove(event.room)
            is Enter -> event.user?.let { rooms[event.room]?.users?.add(it) }
            is Present -> rooms[event.room]?.users?.get(event.user)?.status = event.status
            is Exit -> rooms[event.room]?.users?.remove(event.user)
            else -> {}
        }
    }
}

/** A lobby of plain rosters. */
fun LobbyState(json: Json.MutableObject = Json.MutableObject()): LobbyState<Roster> = LobbyState(json, ::Roster)

/** The lobby's alphabet, beside kroom's: nothing a client posts. An app's lobby extends it. */
open class LobbyEvents<R : Roster>(roster: (Json.MutableObject) -> R) : RoomEvents() {
    val show = event { Show(roster, it) }
    val hide = event(::Hide)
    val enter = event(::Enter)
    val present = event(::Present)
    val exit = event(::Exit)
}

/** The alphabet of a lobby of plain rosters. */
fun LobbyEvents(): LobbyEvents<Roster> = LobbyEvents(::Roster)

/** Decides nothing: a lobby's frames are born by its rooms and its host. An app's lobby with intents extends it. */
open class LobbyLogic<S : LobbyState<*>>(events: LobbyEvents<*>) : Logic<S>(events)

/** The logic of a lobby of plain rosters. */
fun LobbyLogic(): LobbyLogic<LobbyState<Roster>> = LobbyLogic(LobbyEvents())
