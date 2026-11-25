package com.republicate.kroom.server

import com.republicate.kroom.event.Event
import com.republicate.kroom.event.Hide
import com.republicate.kroom.event.LobbyEvents
import com.republicate.kroom.event.LobbyLogic
import com.republicate.kroom.event.LobbyState
import com.republicate.kroom.event.Logic
import com.republicate.kroom.event.Roster
import com.republicate.kroom.event.RosterEvent
import com.republicate.kroom.event.Show
import com.republicate.kson.Json

/**
 * A room of rooms: the scope of a group of rooms (a community), between the process ([Rooms]) and a room.
 * Its State lists the rooms of the group that show ([Room.show]) and who is in each, as their own wire says it.
 * Its [role] says who watches; every frame goes to every watcher. The host emits there what it wants: events of
 * its own, messages to one user.
 *
 * A room names its lobby at construction, and joins the group when [Rooms] takes it. A lobby built on demand
 * does not close while one of its rooms is live.
 */
open class Lobby<R : Roster, S : LobbyState<R>>(id: String, state: S, logic: Logic<S>) : LogicRoom<S>(id, state, logic) {

    init { require(logic.events is LobbyEvents<*>) { "lobby '$id': its events must extend LobbyEvents" } }

    // guarded by itself: rooms come and go from their own threads
    private val live = LinkedHashSet<Room<*>>()
    private var closing = false

    /** Every live room of the group that [Rooms] holds, shown or not, oldest first. On the server alone: never in the State. */
    val rooms: List<Room<*>> get() = synchronized(live) { live.toList() }

    internal fun adopt(room: Room<*>) = synchronized(live) {
        check(running && !closing) { "lobby '$id' is closed: room '${room.id}' cannot join it" }
        live += room
    }

    /** A room of the group stopped: its roster goes, and the lobby may be idle. */
    internal fun release(room: Room<*>) {
        val none = synchronized(live) { live -= room; live.isEmpty() }
        emit(Hide().also { it.room = room.id })
        if (none) idle()
    }

    /** A roster of this lobby's class, for [room]. */
    internal fun roster(room: String): R = state.roster(Json.MutableObject("id" to room))

    /** A room's answer to [Room.show]: its roster whole, or null when it does not show. */
    @Suppress("UNCHECKED_CAST")
    internal fun shown(room: Room<*>, roster: Roster?) {
        if (roster == null) emit(Hide().also { it.room = room.id })
        else emit(Show(state.roster).also { it.roster = roster as R })
    }

    /** An event about a room that does not show is dropped: a hidden room cannot leak through it. */
    override fun emit(event: Event, to: Collection<User>) = step {
        if (event is RosterEvent && event.room !in state.rooms) logger.debug("{} {} dropped: room '{}' does not show", id, event.name, event.room)
        else super.emit(event, to)
    }

    // the decision and the refusal of newcomers are one: a room adopted before it keeps the lobby open
    override fun shouldCloseWhenEmpty(): Boolean = super.shouldCloseWhenEmpty() && synchronized(live) { live.isEmpty().also { closing = it } }
}

/** A lobby of plain rosters. */
fun Lobby(id: String): Lobby<Roster, LobbyState<Roster>> = Lobby(id, LobbyState(), LobbyLogic())
