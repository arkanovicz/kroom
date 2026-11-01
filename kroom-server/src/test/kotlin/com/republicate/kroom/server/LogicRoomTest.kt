package com.republicate.kroom.server

import com.republicate.kroom.event.Cancel
import com.republicate.kroom.event.ChatState
import com.republicate.kroom.event.Event
import com.republicate.kroom.event.JsonView
import com.republicate.kroom.event.Join
import com.republicate.kroom.event.Leave
import com.republicate.kroom.event.Logic
import com.republicate.kroom.event.Reason
import com.republicate.kroom.event.RoomEvents
import com.republicate.kroom.event.Stage
import com.republicate.kroom.event.State
import com.republicate.kroom.event.Status
import com.republicate.kroom.event.done
import com.republicate.kroom.event.emit
import com.republicate.kroom.event.to
import com.republicate.kson.Json
import io.ktor.sse.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*
import com.republicate.kroom.event.Actor as Who

/** Pins a room run by a logic: the outcome is the step, the State the fold (deposits included), presence the room's. */
class LogicRoomTest {

    class Set(json: Json.MutableObject = Json.MutableObject()) : Event("set", json = json) { var n by int().required.out }
    class Note(json: Json.MutableObject = Json.MutableObject()) : Event("note", json = json) {
        var author by string().out
        val text by string().required.visibleTo { it == author }
    }
    class Add(json: Json.MutableObject = Json.MutableObject()) : Event("add", json = json) {
        val by by int().required
        init { check { state: Counter, _ -> if (state.n == 10) Reason("full") else null } }
    }
    class Odd(json: Json.MutableObject = Json.MutableObject()) : Event("odd", json = json) { init { check { _: ChatState<*>, _ -> null } } }
    class Wait(json: Json.MutableObject = Json.MutableObject()) : Event("wait", json = json) { var user by string().out }
    class Whisper(json: Json.MutableObject = Json.MutableObject()) : Event("whisper", json = json) { val who by string().required }
    class Stray(json: Json.MutableObject = Json.MutableObject()) : Event("stray", json = json)
    class Waiter(json: Json.MutableObject = Json.MutableObject()) : JsonView(json) { val id by string().required }

    class Counter(json: Json.MutableObject = Json.MutableObject()) : State(json) {
        var n by int()
            private set
        val notes by log(::Note)
        val waiting by keyed(::Waiter)
        override fun reduce(event: Event) {
            super.reduce(event)
            when (event) {
                is Set -> n = event.n
                is Note -> notes += event
                is Wait -> waiting.add(Waiter(Json.MutableObject("id" to event.user)))
                is Cancel -> if (event.intent == "wait") event.user?.let { waiting.remove(it) }
                is Leave -> event.user?.let { waiting.remove(it.id) }
                else -> {}
            }
        }
    }

    object Events : RoomEvents() {
        val add = intent(::Add)
        val note = intent(::Note)
        val wait = intent(::Wait, cancellable = true)
        val whisper = intent(::Whisper)
        val set = event(::Set)
    }

    object Rule : Logic<Counter>(Events) {
        init {
            on(Events.add) { state, _, add ->
                when {
                    add.by == 7 -> emit(Stray())   // not a name of the room
                    (state.n ?: 0) + add.by > 10 -> Reason("too_big", Json.Object("max" to 10))
                    else -> emit(Set().also { it.n = (state.n ?: 0) + add.by })
                }
            }
            on(Events.note) { _, actor, note -> done.also { note.author = actor.id } }
            on(Events.wait) { state, actor, wait -> if (actor.id in state.waiting) Reason("already_waiting") else done.also { wait.user = actor.id } }
            cancel(Events.wait) { state, actor, _ -> if (actor.id in state.waiting) done else Reason("nothing_to_cancel") }
            on(Events.whisper) { _, _, whisper -> emit(Set().also { it.n = 99 }.to(whisper.who)) }
            on(Events.leave) { _, _, _ -> emit(Set().also { it.n = 0 }) }
        }
    }

    private class TestRoom(id: String) : LogicRoom<Counter>(id, Counter(), Rule) {
        override val leaveGrace = 0L
        override fun shouldCloseWhenEmpty() = false
    }

    private var n = 0
    private fun room() = TestRoom("logic-${n++}")
    private fun user(id: String) = Users.getOrCreate(id, id)
    private fun actor(user: User, tab: String) = Actor(Actor.connectionId(user, tab), user)
    private fun json(vararg pairs: Pair<String, Any?>) = Json.MutableObject(*pairs)

    private suspend fun Channel<ServerSentEvent>.next(): ServerSentEvent = withTimeout(2000) { receive() }
    private suspend fun Channel<ServerSentEvent>.state(): Json.Object = next().also { assertEquals("state", it.event) }.json()
    private suspend fun Channel<ServerSentEvent>.joined() = assertEquals("join", next().event)
    private suspend fun Channel<ServerSentEvent>.silent() { delay(50); assertNull(tryReceive().getOrNull()) }
    private fun ServerSentEvent.json() = Json.parse(data!!) as Json.Object
    private fun Json.Object.objects(key: String) = getArray(key)!!.map { it as Json.Object }

    @BeforeTest fun clean() = Users.clear()

    @Test fun `an outcome frames the intent, then what it emits, each folded into the snapshot`() = runBlocking {
        val room = room()
        val alice = actor(user("alice"), "t1")
        val ca = room.join(alice).also { it.state(); it.joined() }
        val add = room.act(alice, Add(json("by" to 3))).await()
        assertEquals(Stage.APPLIED, add.stage)
        val first = ca.next().also { assertEquals("add", it.event); assertEquals(3, it.json().getInt("by")) }
        ca.next().let {
            assertEquals("set", it.event)
            assertEquals(3, it.json().getInt("n"))
            assertEquals(first.id!!.toLong() + 1, it.id!!.toLong())
        }
        val snapshot = room.join(actor(user("bob"), "t1")).state()
        assertEquals(3, snapshot.getInt("n"))
        // presence is the fold of its frames: the joiner is not in its own snapshot, its `join` follows
        val users = snapshot.getObject("users")!!.values.map { it as Json.Object }.associateBy { it.getString("id") }
        assertEquals(setOf("alice"), users.keys)
        users.values.forEach { u ->
            assertEquals("ONLINE", u.getString("status"))
            assertEquals(listOf("t1"), u.objects("clients").map { it.getString("id") })
        }
        assertNull(snapshot["pending"])   // nothing held by the room
        assertEquals("bob", snapshot.getObject("me")!!.getString("id"))   // the viewer, as its users entry
    }

    @Test fun `the check refuses first, on the loop, and a room refuses an event checked on another State`() = runBlocking {
        val room = room()
        val alice = actor(user("alice"), "t1")
        val ca = room.join(alice).also { it.state(); it.joined() }
        room.act(alice, Add(json("by" to 10))).await()
        ca.next(); ca.next()   // add, set
        room.act(alice, Add(json("by" to 1))).await().let { assertEquals(Stage.REJECTED, it.stage); assertEquals("full", it.meta.reason) }   // not the logic's too_big
        ca.silent()
        val oddEvents = object : RoomEvents() { val odd = intent(::Odd) }
        val odd = object : Logic<Counter>(oddEvents) { init { on(oddEvents.odd) { _, _, _ -> done } } }
        assertFailsWith<IllegalArgumentException> { LogicRoom("odd-${n++}", Counter(), odd) }
        val partial = object : Logic<Counter>(Events) { init { on(Events.add) { _, _, _ -> done } } }
        assertFailsWith<IllegalArgumentException> { LogicRoom("partial-${n++}", Counter(), partial) }   // a room is built covered or not at all
    }

    @Test fun `a reason is the verdict alone, nothing framed, nothing changed`() = runBlocking {
        val room = room()
        val alice = actor(user("alice"), "t1")
        val ca = room.join(alice).also { it.state(); it.joined() }
        val add = room.act(alice, Add(json("by" to 20))).await()
        assertEquals(Stage.REJECTED, add.stage)
        assertEquals("too_big", add.meta.reason)
        assertEquals(10, add.meta.args!!.getInt("max"))
        ca.silent()
        assertNull(room.join(actor(user("bob"), "t1")).state().getInt("n"))
    }

    @Test fun `a cancellable intent's deposit is the State's, cancel withdraws it`() = runBlocking {
        val room = room()
        val alice = user("alice")
        val t1 = actor(alice, "t1")
        val c1 = room.join(t1).also { it.state(); it.joined() }
        val wait = room.act(t1, Wait()).await()
        assertEquals(Stage.APPLIED, wait.stage)
        assertEquals("wait", c1.next().event)
        // the deposit is the user's, in the State: another tab of theirs sees it
        assertEquals(setOf("alice"), room.join(actor(alice, "t2")).state().getObject("waiting")!!.keys)
        assertEquals("already_waiting", room.act(t1, Wait()).await().meta.reason)
        assertEquals("not_cancellable", room.act(t1, Cancel(json("intent" to "add"))).await().meta.reason)
        val cancel = room.act(t1, Cancel(json("intent" to "wait"))).await()
        assertEquals(Stage.APPLIED, cancel.stage)
        c1.next().let {
            assertEquals("cancel", it.event)
            assertEquals("wait", it.json().getString("intent"))
            assertEquals("alice", it.json().getString("user"))
        }
        assertEquals("nothing_to_cancel", room.act(t1, Cancel(json("intent" to "wait"))).await().meta.reason)
        assertEquals(emptySet(), room.join(actor(alice, "t3")).state().getObject("waiting")!!.keys)
        c1.silent()
    }

    @Test fun `the logic's errors are refused, not framed`() = runBlocking {
        val room = room()
        val alice = actor(user("alice"), "t1")
        val ca = room.join(alice).also { it.state(); it.joined() }
        room.act(alice, Add(json("by" to 7))).await().let { assertEquals(Stage.REJECTED, it.stage); assertEquals("error", it.meta.reason) }
        ca.silent()
    }

    @Test fun `a selective field reaches the users its rule names, all their tabs, in one frame per user`() = runBlocking {
        val room = room()
        val alice = user("alice")
        val t1 = actor(alice, "t1")
        val c1 = room.join(t1).also { it.state(); it.joined() }
        val c2 = room.join(actor(alice, "t2")).also { it.state() }
        val cb = room.join(actor(user("bob"), "t1")).also { it.state(); it.joined() }
        c1.joined(); c2.joined() // bob's
        room.act(t1, Note(json("text" to "secret"))).await()
        val frames = listOf(c1, c2, cb).map { it.next() }
        frames.forEach { assertEquals("note", it.event) }
        assertEquals(listOf("secret", "secret", null), frames.map { it.json().getString("text") })
        frames.forEach { assertEquals("alice", it.json().getString("author")) }
        assertSame(frames[0], frames[1])   // serialized once for alice's tabs
        assertEquals(1, frames.map { it.id }.toSet().size)
        assertEquals("secret", room.state.notes.single().text)   // the room's fold has it
        assertEquals(null, room.stateToJson(actor(user("bob"), "t1")).getArray("notes")!!.getObject(0)!!.getString("text"))   // so does alice's snapshot, not bob's
        assertEquals("secret", room.stateToJson(t1).getArray("notes")!!.getObject(0)!!.getString("text"))
    }

    @Test fun `to names the users, and such a frame is not folded`() = runBlocking {
        val room = room()
        val alice = actor(user("alice"), "t1")
        val ca = room.join(alice).also { it.state(); it.joined() }
        val cb = room.join(actor(user("bob"), "t1")).also { it.state(); it.joined() }
        ca.joined() // bob's
        room.act(alice, Whisper(json("who" to "bob"))).await()
        assertEquals("whisper", ca.next().event)
        assertEquals("whisper", cb.next().event)
        cb.next().let { assertEquals("set", it.event); assertEquals(99, it.json().getInt("n")) }
        ca.silent()
        // a message, not a transition of the shared marking: the snapshot holds what everyone got
        val cc = room.join(actor(user("carol"), "t1"))
        assertNull(cc.next().json()["n"])
    }

    @Test fun `status composes the user's from its clients'`() = runBlocking {
        val room = room()
        val alice = user("alice")
        val t1 = actor(alice, "t1"); val t2 = actor(alice, "t2")
        val c1 = room.join(t1).also { it.state(); it.joined() }
        room.join(t2).state()
        suspend fun post(who: Actor, status: String): Json.Object {
            assertEquals(Stage.APPLIED, room.act(who, Status(json("status" to status))).await().stage)
            return c1.next().also { assertEquals("status", it.event) }.json()
        }
        post(t2, "IDLE").let {
            assertEquals("alice", it.getString("user"))
            assertEquals("ONLINE", it.getString("status")) // t1 still online
            val client = it.getObject("client")!!
            assertEquals("t2", client.getString("id"))
            assertEquals("web", client.getString("kind"))
            assertEquals("IDLE", client.getString("status"))
        }
        assertEquals("IDLE", post(t1, "IDLE").getString("status"))
        assertEquals("IDLE", post(t1, "AWAY").getString("status")) // t2 still idle
    }

    @Test fun `a leave is framed, then what the logic emits on it`() = runBlocking {
        val room = room()
        val alice = actor(user("alice"), "t1")
        room.join(alice).also { it.state(); it.joined() }
        val cb = room.join(actor(user("bob"), "t1")).also { it.state(); it.joined() }
        room.leave(alice)
        cb.next().let {
            assertEquals("leave", it.event)
            assertEquals("alice", it.json().getObject("user")!!.getString("id"))
            assertEquals(true, it.json().getBoolean("last"))
            assertEquals(1, it.json().getInt("count"))
        }
        cb.next().let { assertEquals("set", it.event); assertEquals(0, it.json().getInt("n")) }
        cb.silent()
    }

    @Test fun `the room describes the state and the alphabet`() {
        val d = room().describe()
        assertEquals(listOf("users", "n", "notes", "waiting"), d.objects("state").map { it.getString("name") })
        val events = d.objects("events").associateBy { it.getString("name")!! }
        assertEquals(false, events["set"]!!.getBoolean("postable"))
        assertEquals(true, events["wait"]!!.getBoolean("cancellable"))
        assertEquals(true, events["status"]!!.getBoolean("postable"))
        assertEquals(true, events["cancel"]!!.getBoolean("postable"))
        assertEquals(listOf(false, true), events["note"]!!.objects("fields").map { it.getBoolean("selective") })
    }

    @Test fun `a spectator is refused at the door`() = runBlocking {
        val room = room()
        val alice = user("alice")
        val spectator = Spectator(Actor.connectionId(alice, "t1"), alice)
        room.joinAsSpectator(spectator).state()
        assertEquals("spectator", room.act(spectator, Add(json("by" to 1))).await().meta.reason)
    }
}
