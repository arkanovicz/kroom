package com.republicate.kroom.server

import com.republicate.kroom.event.Cancel
import com.republicate.kroom.event.Event
import com.republicate.kroom.event.Fires
import com.republicate.kroom.event.Join
import com.republicate.kroom.event.Leave
import com.republicate.kroom.event.Logic
import com.republicate.kroom.event.Outcome
import com.republicate.kroom.event.Reason
import com.republicate.kroom.event.Registry
import com.republicate.kroom.event.Stage
import com.republicate.kroom.event.State
import com.republicate.kroom.event.Status
import com.republicate.kroom.event.done
import com.republicate.kroom.event.fired
import com.republicate.kroom.event.held
import com.republicate.kroom.event.to
import com.republicate.kson.Json
import io.ktor.sse.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*
import com.republicate.kroom.event.Actor as Who

/** Pins a room run by a rule: the outcome is the step, the state the fold, presence and tokens the room's. */
class LogicRoomTest {

    class Set(json: Json.MutableObject = Json.MutableObject()) : Event("set", json = json) { var n by int().required.out }
    class Note(json: Json.MutableObject = Json.MutableObject()) : Event("note", json = json) { val text by string().required.mine }
    class Add(json: Json.MutableObject = Json.MutableObject()) : Event("add", fires = Fires(::Set), json = json) { val by by int().required }
    class Wait(json: Json.MutableObject = Json.MutableObject()) : Event("wait", fires = Fires(::Set), pending = true, json = json)
    class Whisper(json: Json.MutableObject = Json.MutableObject()) : Event("whisper", fires = Fires(::Set), json = json) { val who by string().required }
    class Oops(json: Json.MutableObject = Json.MutableObject()) : Event("oops", json = json)

    class Counter(json: Json.MutableObject = Json.MutableObject()) : State(json) {
        var n by int()
            private set
        val notes by log(::Note)
        override fun reduce(frame: Event) = when (frame) {
            is Set -> n = frame.n
            is Note -> notes += frame
            else -> {}
        }
    }

    object Rule : Logic<Counter> {
        override val registry = Registry(listOf(::Add, ::Note, ::Wait, ::Whisper, ::Oops), leave = Fires(::Set))
        override fun fire(state: Counter, actor: Who, intent: Event): Outcome<Event> = when (intent) {
            is Add -> when {
                intent.by == 7 -> fired(Note(Json.MutableObject("text" to "seven"))) // not among add's fires
                (state.n ?: 0) + intent.by > 10 -> Reason("too_big", Json.Object("max" to 10))
                else -> fired(Set().also { it.n = (state.n ?: 0) + intent.by })
            }
            is Note -> done
            is Wait -> held()
            is Whisper -> fired(Set().also { it.n = 99 }.to(intent.who))
            is Oops -> held() // not declared pending
            is Leave -> fired(Set().also { it.n = 0 })
            is Join, is Status -> done
            else -> Reason("unknown")
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

    @Test fun `an outcome frames the intent, then what it fired, each folded into the snapshot`() = runBlocking {
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
        val users = snapshot.objects("users").associateBy { it.getString("id") }
        assertEquals(setOf("alice", "bob"), users.keys)
        users.values.forEach { u ->
            assertEquals("ONLINE", u.getString("status"))
            assertEquals(listOf("t1"), u.objects("clients").map { it.getString("id") })
        }
        assertEquals(emptyList(), snapshot.getArray("pending")!!.toList())
        assertEquals("bob", snapshot.getObject("me")!!.getString("id"))   // the viewer, as its users entry
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

    @Test fun `a held intent stays pending until cancelled`() = runBlocking {
        val room = room()
        val alice = user("alice")
        val t1 = actor(alice, "t1")
        val c1 = room.join(t1).also { it.state(); it.joined() }
        val wait = room.act(t1, Wait()).await()
        assertEquals(Stage.APPLIED, wait.stage)
        assertEquals(true, wait.meta.held)
        assertEquals("wait", c1.next().event)
        // the token is the user's: another tab of theirs sees it
        assertEquals(listOf("wait"), room.join(actor(alice, "t2")).state().getArray("pending")!!.toList())
        assertEquals("already_pending", room.act(t1, Wait()).await().meta.reason)
        val cancel = room.act(t1, Cancel(json("intent" to "wait"))).await()
        assertEquals(Stage.APPLIED, cancel.stage)
        c1.next().let {
            assertEquals("cancel", it.event)
            assertEquals("wait", it.json().getString("intent"))
            assertEquals("alice", it.json().getString("user"))
        }
        assertEquals("not_pending", room.act(t1, Cancel(json("intent" to "wait"))).await().meta.reason)
        assertEquals(emptyList(), room.join(actor(alice, "t3")).state().getArray("pending")!!.toList())
        c1.silent()
    }

    @Test fun `the rule's errors are refused, not framed`() = runBlocking {
        val room = room()
        val alice = actor(user("alice"), "t1")
        val ca = room.join(alice).also { it.state(); it.joined() }
        room.act(alice, Add(json("by" to 7))).await().let { assertEquals(Stage.REJECTED, it.stage); assertEquals("error", it.meta.reason) }
        room.act(alice, Oops()).await().let { assertEquals(Stage.REJECTED, it.stage); assertEquals("error", it.meta.reason) }
        ca.silent()
    }

    @Test fun `mine reaches the author whole, the others stripped`() = runBlocking {
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
        assertEquals("secret", frames[0].json().getString("text"))
        assertEquals("secret", frames[1].json().getString("text"))
        assertEquals("{}", frames[2].data)
        assertEquals(1, frames.map { it.id }.toSet().size)
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

    @Test fun `a leave is framed, then what the rule fired on it`() = runBlocking {
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

    @Test fun `the registry describes the state and the alphabet`() {
        val d = room().describe()
        assertEquals(listOf("n", "notes"), d.objects("state").map { it.getString("name") })
        val events = d.objects("events").associateBy { it.getString("name")!! }
        assertEquals(false, events["set"]!!.getBoolean("postable"))
        assertEquals(listOf("set"), events["add"]!!.getArray("fires")!!.toList())
        assertEquals(true, events["wait"]!!.getBoolean("pending"))
        assertEquals(true, events["status"]!!.getBoolean("postable"))
        assertEquals(true, events["cancel"]!!.getBoolean("postable"))
        assertEquals(true, events["note"]!!.objects("fields").single().getBoolean("mine"))
    }

    @Test fun `a spectator is refused at the door`() = runBlocking {
        val room = room()
        val alice = user("alice")
        val spectator = Spectator(Actor.connectionId(alice, "t1"), alice)
        room.joinAsSpectator(spectator).state()
        assertEquals("spectator", room.act(spectator, Add(json("by" to 1))).await().meta.reason)
    }
}
