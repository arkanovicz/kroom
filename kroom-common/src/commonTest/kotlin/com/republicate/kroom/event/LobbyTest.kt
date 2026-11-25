package com.republicate.kroom.event

import com.republicate.kson.Json
import kotlin.test.*

/** Pins a lobby's fold: a roster shown whole, moved by its room's presence, gone at `hide`; an app's roster travels. */
class LobbyTest {

    /** An app's roster: who is in the room's call. */
    private class Voiced(json: Json.MutableObject = Json.MutableObject()) : Roster(json) {
        val call by keyed(::Caller)
    }
    private class Caller(json: Json.MutableObject = Json.MutableObject()) : JsonView(json) { var id by string().required }
    private class Listen(json: Json.MutableObject = Json.MutableObject()) : RosterEvent("listen", json) { var user by string().required.out }

    private class VoicedState : LobbyState<Voiced>(roster = ::Voiced) {
        override fun reduce(event: Event) {
            super.reduce(event)
            if (event is Listen) rooms[event.room]?.call?.add(Caller().also { it.id = event.user })
        }
    }

    private fun presence(id: String) = UserPresence().also { it.id = id; it.name = id; it.status = Presence.ONLINE }
    private fun show(room: String, vararg users: String) = Show(::Roster).also { s ->
        s.roster = Roster().also { r -> r.id = room; users.forEach { r.users.add(presence(it)) } }
    }
    private fun enter(room: String, user: String) = Enter().also { it.room = room; it.user = presence(user) }
    private fun exit(room: String, user: String) = Exit().also { it.room = room; it.user = user }
    private fun hide(room: String) = Hide().also { it.room = room }

    private fun LobbyState<*>.who(room: String) = rooms[room]?.users?.map { it.id }

    @Test fun `a roster is shown whole, moved by enter, present and exit, gone at hide`() {
        val state = LobbyState()
        state.reduce(show("12", "alice"))
        assertEquals(listOf("alice"), state.who("12"))
        state.reduce(enter("12", "bob"))
        assertEquals(listOf("alice", "bob"), state.who("12"))
        state.reduce(Present().also { it.room = "12"; it.user = "alice"; it.status = Presence.LOST })
        assertEquals(Presence.LOST, state.rooms["12"]!!.users["alice"]!!.status)
        assertEquals(Presence.ONLINE, state.rooms["12"]!!.users["bob"]!!.status)
        state.reduce(exit("12", "alice"))
        assertEquals(listOf("bob"), state.who("12"))
        state.reduce(hide("12"))
        assertNull(state.rooms["12"])
    }

    @Test fun `an enter or an exit that names nobody is a guest's, counted on the roster`() {
        val state = LobbyState()
        state.reduce(show("12", "alice"))
        assertNull(state.rooms["12"]!!.guests)
        state.reduce(Enter().also { it.room = "12" })
        state.reduce(Enter().also { it.room = "12" })
        assertEquals(2, state.rooms["12"]!!.guests)
        assertEquals(listOf("alice"), state.who("12"))
        state.reduce(Exit().also { it.room = "12" })
        assertEquals(1, state.rooms["12"]!!.guests)
        // a roster restated says its count whole
        state.reduce(Show(::Roster).also { s -> s.roster = Roster().also { it.id = "12"; it.guests = 4 } })
        assertEquals(4, state.rooms["12"]!!.guests)
    }

    @Test fun `a roster restated replaces the one shown and keeps its rank`() {
        val state = LobbyState()
        state.reduce(show("12", "alice"))
        state.reduce(show("13"))
        state.reduce(show("12", "bob", "carol"))
        assertEquals(listOf("12", "13"), state.rooms.map { it.id })
        assertEquals(listOf("bob", "carol"), state.who("12"))
    }

    @Test fun `an event about a room that is not shown changes nothing`() {
        val state = LobbyState()
        state.reduce(enter("12", "alice"))
        state.reduce(exit("12", "alice"))
        state.reduce(hide("12"))
        assertEquals("{}", state.rooms.obj.toString())
    }

    @Test fun `an app's roster carries its own fields, moved by its own events`() {
        val state = VoicedState()
        state.reduce(Show(::Voiced).also { s -> s.roster = Voiced().also { it.id = "12"; it.call.add(Caller().also { c -> c.id = "alice" }) } })
        state.reduce(Listen().also { it.room = "12"; it.user = "bob" })
        assertEquals(listOf("alice", "bob"), state.rooms["12"]!!.call.map { it.id })
        // rebuilt from a snapshot's JSON, as a browser does: the app's class reads its fields
        val copy = VoicedState().also { it.json.putAll(state.json.copy()) }
        assertEquals(listOf("alice", "bob"), copy.rooms["12"]!!.call.map { it.id })
    }

    @Test fun `the fold marks the roster and the user it wrote under`() {
        val state = LobbyState().also { it.dirty = HashSet() }
        state.reduce(show("12", "alice"))
        state.reduce(show("13"))
        state.dirty!!.clear()
        state.reduce(enter("12", "bob"))
        assertEquals<Set<Any>?>(setOf("12", "bob"), state.dirty)
    }

    @Test fun `nothing of the lobby's alphabet is postable`() {
        val events = LobbyEvents()
        assertEquals(setOf("status", "cancel"), events.names)
        assertTrue(events.all.containsAll(listOf("show", "hide", "enter", "present", "exit")))
        assertNull(events.of("enter", Json.MutableObject()))
    }
}
