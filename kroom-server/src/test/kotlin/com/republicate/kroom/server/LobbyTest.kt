package com.republicate.kroom.server

import com.republicate.kroom.event.Event
import com.republicate.kroom.event.JsonView
import com.republicate.kroom.event.LobbyEvents
import com.republicate.kroom.event.LobbyLogic
import com.republicate.kroom.event.LobbyState
import com.republicate.kroom.event.Logic
import com.republicate.kroom.event.RoomEvents
import com.republicate.kroom.event.Roster
import com.republicate.kroom.event.RosterEvent
import com.republicate.kroom.event.State
import com.republicate.kroom.event.Status
import com.republicate.kroom.event.to
import com.republicate.kson.Json
import io.ktor.sse.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** Pins a lobby: a room that shows is a roster moved by its own presence frames, a room that does not leaves no trace. */
class LobbyTest {

    class Blank : State()
    object Quiet : Logic<Blank>(RoomEvents())

    /** A room of a lobby; [open] is its answer to `show`. */
    private open class Den(id: String, lobby: Lobby<*, *>, @Volatile var open: Boolean = true) : LogicRoom<Blank>(id, Blank(), Quiet, lobby) {
        override val leaveGrace = 0L
        override fun shouldCloseWhenEmpty() = false
        override fun show(roster: Roster) = open
    }

    // an app's lobby: its rosters say who is in the room's call, its host rings and writes to one user
    class Caller(json: Json.MutableObject = Json.MutableObject()) : JsonView(json) { var id by string().required }
    class Voiced(json: Json.MutableObject = Json.MutableObject()) : Roster(json) { val call by keyed(::Caller) }
    class Listen(json: Json.MutableObject = Json.MutableObject()) : RosterEvent("listen", json) { var user by string().required.out }
    class Digest(json: Json.MutableObject = Json.MutableObject()) : Event("digest", json = json) { var unread by int().out }
    object VoicedEvents : LobbyEvents<Voiced>(::Voiced) {
        val listen = event(::Listen)
        val digest = event(::Digest)
    }
    class VoicedState : LobbyState<Voiced>(roster = ::Voiced) {
        override fun reduce(event: Event) {
            super.reduce(event)
            if (event is Listen) rooms[event.room]?.call?.add(Caller().also { it.id = event.user })
        }
    }
    private class Booth(id: String, lobby: Lobby<*, *>, open: Boolean) : Den(id, lobby, open) {
        override fun show(roster: Roster) = open.also { (roster as Voiced).call.add(Caller().also { it.id = "carol" }) }
    }

    private var n = 0
    private fun lobby() = Lobby("lobby-${n++}")
    private fun user(id: String) = Users.getOrCreate(id, id)
    private fun actor(id: String, tab: String = "t1") = user(id).let { Actor(Actor.connectionId(it, tab), it) }
    private fun json(vararg pairs: Pair<String, Any?>) = Json.MutableObject(*pairs)

    private suspend fun Channel<ServerSentEvent>.next(): ServerSentEvent = withTimeout(2000) { receive() }
    private suspend fun Channel<ServerSentEvent>.next(name: String): Json.Object = next().also { assertEquals(name, it.event) }.json()
    private suspend fun Channel<ServerSentEvent>.silent() { delay(100); assertNull(tryReceive().getOrNull()?.let { "${it.event} ${it.data}" }) }
    private fun ServerSentEvent.json() = Json.parse(data!!) as Json.Object
    private suspend fun Room<*>.watch(id: String) = join(actor(id)).also { it.next("state"); it.next("join") }
    private fun Json.Object.ids(key: String) = getObject(key)!!.keys.toList()

    @BeforeTest fun clean() = Users.clear()

    @Test fun `a room that shows is a roster, moved by what its own wire says of presence`() = runBlocking {
        val lobby = lobby()
        val wanda = lobby.watch("wanda")
        val den = Den("den-${n++}", lobby)
        wanda.silent()   // a room nobody is in has no roster yet

        den.join(actor("alice"))
        wanda.next("show").getObject("roster")!!.let {
            assertEquals(den.id, it.getString("id"))
            assertEquals(listOf("alice"), it.ids("users"))
        }
        den.join(actor("alice", "t2"))   // a second tab of hers: the room's wire says nothing, nor does the lobby's
        wanda.silent()

        val bob = actor("bob")
        den.join(bob)
        wanda.next("enter").let {
            assertEquals(den.id, it.getString("room"))
            assertEquals("bob", it.getObject("user")!!.getString("id"))
        }
        den.act(bob, Status(json("status" to "IDLE"))).await()
        wanda.next("present").let {
            assertEquals(den.id, it.getString("room"))
            assertEquals("bob", it.getString("user"))
            assertEquals("IDLE", it.getString("status"))
        }

        // a watcher come late reads the same in its snapshot
        val late = lobby.join(actor("walt")).next("state").getObject("rooms")!!.getObject(den.id)!!
        assertEquals(listOf("alice", "bob"), late.ids("users"))
        assertEquals("IDLE", late.getObject("users")!!.getObject("bob")!!.getString("status"))
        wanda.next("join")

        den.leave(bob)
        wanda.next("exit").let {
            assertEquals(den.id, it.getString("room"))
            assertEquals("bob", it.getString("user"))
        }
        den.stop()
        assertEquals(den.id, wanda.next("hide").getString("room"))
        wanda.silent()
        lobby.stop()
    }

    @Test fun `a room's guests are counted on its roster, and a room of guests alone shows`() = runBlocking {
        val lobby = lobby()
        val wanda = lobby.watch("wanda")
        val den = Den("den-${n++}", lobby)
        val first = Spectator("guest-${n++}")
        den.join(first)
        wanda.next("show").getObject("roster")!!.let {
            assertEquals(den.id, it.getString("id"))
            assertEquals(1, it.getInt("guests"))
            assertEquals(0, it.getObject("users")?.size ?: 0)
        }
        den.join(Spectator("guest-${n++}"))
        wanda.next("enter").let {
            assertEquals(den.id, it.getString("room"))
            assertNull(it.getObject("user"))
        }
        den.join(actor("alice"))
        assertEquals("alice", wanda.next("enter").getObject("user")!!.getString("id"))

        // a watcher come late reads the count in its snapshot; without a user it is a guest of the lobby itself
        val late = lobby.join(Spectator("guest-${n++}")).next("state")
        assertEquals(2, late.getObject("rooms")!!.getObject(den.id)!!.getInt("guests"))
        assertNull(wanda.next("join").getObject("user"))
        assertEquals(1, lobby.state.guests)

        den.leave(first)
        wanda.next("exit").let {
            assertEquals(den.id, it.getString("room"))
            assertNull(it.getString("user"))
        }
        assertEquals(1, lobby.state.rooms[den.id]!!.guests)
        den.stop(); lobby.stop()
    }

    @Test fun `a roster is the room's that showed it, whatever a room closing late under the same id says`() = runBlocking {
        val lobby = lobby()
        val wanda = lobby.watch("wanda")
        val id = "den-${n++}"
        val old = Den(id, lobby)
        old.join(actor("alice"))
        assertEquals(listOf("alice"), wanda.next("show").getObject("roster")!!.ids("users"))

        // rebuilt under the id while the first one is still closing: the roster is the new room's
        val rebuilt = Den(id, lobby)
        rebuilt.join(actor("bob"))
        assertEquals(listOf("bob"), wanda.next("show").getObject("roster")!!.ids("users"))
        old.join(actor("carol"))   // what the old one still says moves nothing
        old.stop()                 // nor does its close hide anything
        wanda.silent()
        assertEquals(listOf("bob"), lobby.state.rooms[id]!!.users.map { it.id })
        rebuilt.join(actor("dave"))
        assertEquals("dave", wanda.next("enter").getObject("user")!!.getString("id"))

        // closed before the next one shows: the roster goes at once, the next room's comes with its first user
        val third = Den(id, lobby)
        rebuilt.stop()
        assertEquals(id, wanda.next("hide").getString("room"))
        third.join(actor("erin"))
        assertEquals(listOf("erin"), wanda.next("show").getObject("roster")!!.ids("users"))
        third.stop()
        assertEquals(id, wanda.next("hide").getString("room"))
        wanda.silent()
        lobby.stop()
    }

    @Test fun `a room that does not show leaves no trace, until it announces otherwise`() = runBlocking {
        val lobby = Lobby("lobby-${n++}", VoicedState(), LobbyLogic<VoicedState>(VoicedEvents))
        val wanda = lobby.watch("wanda")
        val booth = Booth("booth-${n++}", lobby, open = false)
        val alice = actor("alice")
        booth.join(alice)
        booth.act(alice, Status(json("status" to "AWAY"))).await()
        lobby.emit(Listen().also { it.room = booth.id; it.user = "alice" })   // the app's own event about it: dropped
        wanda.silent()
        assertEquals("{}", lobby.join(actor("walt")).next("state").getObject("rooms").toString())
        wanda.next("join")

        // its access changed: the roster whole, with what the room adds to it
        booth.open = true
        booth.announce()
        wanda.next("show").getObject("roster")!!.let {
            assertEquals(listOf("alice"), it.ids("users"))
            assertEquals("AWAY", it.getObject("users")!!.getObject("alice")!!.getString("status"))
            assertEquals(listOf("carol"), it.ids("call"))
        }
        lobby.emit(Listen().also { it.room = booth.id; it.user = "alice" })
        assertEquals("alice", wanda.next("listen").getString("user"))
        assertEquals(listOf("carol", "alice"), lobby.state.rooms[booth.id]!!.call.map { it.id })

        // and back: gone, and silent again
        booth.open = false
        booth.announce()
        assertEquals(booth.id, wanda.next("hide").getString("room"))
        booth.join(actor("bob"))
        wanda.silent()
        booth.stop(); lobby.stop()
    }

    @Test fun `the host writes to one watcher, and to all`() = runBlocking {
        val lobby = Lobby("lobby-${n++}", VoicedState(), LobbyLogic<VoicedState>(VoicedEvents))
        val wanda = lobby.watch("wanda")
        val walt = lobby.join(actor("walt")).also { it.next("state"); it.next("join") }
        wanda.next("join")
        lobby.emit(Digest().also { it.unread = 3 }.to("walt"))
        assertEquals(3, walt.next("digest").getInt("unread"))
        wanda.silent()
        lobby.emit(Digest().also { it.unread = 0 })
        wanda.next("digest"); walt.next("digest")
        lobby.stop()
    }

    private class Passing(id: String) : Lobby<Roster, LobbyState<Roster>>(id, LobbyState(), LobbyLogic()) {
        override val idleTimeout get() = 100L
    }

    @Test fun `a lobby knows its rooms, shown or not, and stays while one of them lives`() = runBlocking {
        val lid = "lobby-${n++}"
        val rid = "den-${n++}"
        // a room's build fetches its lobby through Rooms, under the same lock
        val den = withTimeout(2000) {
            Rooms.getOrCreate(rid) { id ->
                val lobby = Rooms.getOrCreate(lid) { Passing(it) } as Lobby<*, *>
                object : Den(id, lobby, open = false) {
                    override val idleTimeout get() = 400L
                    override fun shouldCloseWhenEmpty() = true
                }
            }!!
        }
        val lobby = Rooms.get<LobbyState<Roster>>(lid) as Lobby<*, *>
        assertSame(lobby, den.lobby)
        assertEquals(listOf<Room<*>>(den), lobby.rooms)
        delay(250)   // well past the lobby's own timeout: its room keeps it
        assertTrue(lobby.running && den.running)
        withTimeout(2000) { while (den.running) delay(10) }
        assertEquals(emptyList(), lobby.rooms)
        withTimeout(2000) { while (lobby.running) delay(10) }
        assertFalse(Rooms.exists(lid))
    }

    @Test fun `a lobby's events must be a lobby's`() {
        class Plain : LobbyState<Roster>(roster = ::Roster)
        assertFailsWith<IllegalArgumentException> { Lobby("lobby-${n++}", Plain(), object : Logic<Plain>(RoomEvents()) {}) }
    }
}
