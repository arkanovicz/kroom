package com.republicate.kroom.server

import com.republicate.kroom.event.Audience
import com.republicate.kroom.event.Event
import com.republicate.kroom.event.Join
import com.republicate.kroom.event.Leave
import com.republicate.kroom.event.Reason
import com.republicate.kroom.event.Stage
import com.republicate.kson.Json
import io.ktor.sse.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** Pins the room contract over Event: the door, the loop, the audience, the frame, stable tabs. */
class RoomTest {

    class Ok(json: Json.MutableObject = Json.MutableObject()) : Event("ok", Audience.USERS, json = json)
    class Loud(json: Json.MutableObject = Json.MutableObject()) : Event("loud", json = json)
    class Bad(json: Json.MutableObject = Json.MutableObject()) : Event("bad", json = json)
    class Boom(json: Json.MutableObject = Json.MutableObject()) : Event("boom", json = json)
    class Shaped(json: Json.MutableObject = Json.MutableObject()) : Event("shaped", json = json) { val n by int().required }
    class Vip(json: Json.MutableObject = Json.MutableObject()) : Event("vip", permission = "vip", json = json)
    class Slow(json: Json.MutableObject = Json.MutableObject()) : Event("slow", json = json)

    private class TestRoom(id: String, val grace: Long = 0, val closes: Boolean = false) : Room<Json.MutableObject>(id) {
        override var state = Json.MutableObject()
        override fun shouldCloseWhenEmpty() = closes
        override val idleTimeout get() = 50L
        override val channelCapacity = 8
        override val leaveGrace = grace
        override fun stateToJson(): Json.Object = Json.Object()
        override fun stateToJson(actor: Actor): Json.Object = Json.Object("you" to actor.name, "n" to state.getInt("n"))
        override fun permits(user: User, permission: String) = user.userId == "alice"
        var onJoin: (Actor) -> Unit = {}
        var onSlow: () -> Unit = {}
        init {
            // the state changes, then (after a pause the test controls) the frames describing it
            on(::Slow) { _, _ -> state["n"] = (state.getInt("n") ?: 0) + 1; onSlow(); broadcast("moved", Json.Object()); null }
            on(::Ok) { actor, _ -> broadcast("moved", Json.Object("by" to actor.name)); null }
            on(::Loud) { _, _ -> null }
            on(::Bad) { _, _ -> Reason("not_your_turn", Json.Object("turn" to "bob")) }
            on(::Boom) { _, _ -> throw IllegalStateException("kaboom") }
            on(::Shaped) { _, _ -> null }
            on(::Vip) { _, _ -> null }
            on(::Join) { actor, _ -> onJoin(actor); null }
            on(::Leave) { actor, leave -> if (leave.last == true) broadcast("gone", Json.Object("who" to actor.name)); null }
        }
    }

    private var n = 0
    private fun room() = TestRoom("room-${n++}")
    private fun user(id: String) = Users.getOrCreate(id, id)
    private fun actor(user: User, tab: String) = Actor(Actor.connectionId(user, tab), user)
    private fun json(vararg pairs: Pair<String, Any?>) = Json.MutableObject(*pairs)

    private suspend fun Channel<ServerSentEvent>.next(): ServerSentEvent = withTimeout(2000) { receive() }
    private suspend fun Channel<ServerSentEvent>.state() = assertEquals("state", next().event)
    private fun ServerSentEvent.json() = Json.parse(data!!) as Json.Object

    @BeforeTest fun clean() = Users.clear()

    @Test fun `an applied event is framed after the handler's own emissions, payload only`() = runBlocking {
        val room = room()
        val a = actor(user("u"), "t1")
        val ch = room.join(a).also { it.state() }
        ch.drain()
        val verdict = room.act(a, Ok()).await()
        assertNotEquals(Stage.REJECTED, verdict.stage)
        assertEquals("u", verdict.meta.from)   // the object's, server-side
        assertEquals("t1", verdict.meta.tab)
        val moved = ch.next()
        assertEquals("moved", moved.event)
        ch.next().let {
            assertEquals("ok", it.event)
            assertNull(it.json()["_"])
            assertEquals(verdict.meta.seq.toString(), it.id)
            assertEquals(moved.id!!.toLong() + 1, it.id!!.toLong())
        }
    }

    @Test fun `an anonymous spectator keeps a closing room open`() = runBlocking {
        val room = TestRoom("room-${n++}", closes = true)
        val watcher = room.join(Spectator("anon-1")).also { it.state() }
        val alice = Actor(Actor.connectionId(user("alice"), "t1"), user("alice"))
        room.join(alice).also { it.state() }
        room.leave(alice)
        room.broadcast("still", Json.Object())
        while (watcher.next().event != "still") {}
        room.leave(room.getActor("anon-1")!!)
        withTimeout(2000) { while (room.running) delay(10) }   // the last one gone, then idle: stopped
        assertTrue(room.join(alice).isClosedForReceive)
    }

    @Test fun `a rejection is the verdict alone, nothing is framed`() = runBlocking {
        val room = room()
        val u = user("u")
        val t1 = actor(u, "t1"); val t2 = actor(u, "t2"); val v = actor(user("v"), "t1")
        val c1 = room.join(t1).also { it.state() }
        val c2 = room.join(t2).also { it.state() }
        val cv = room.join(v).also { it.state() }
        listOf(c1, c2, cv).forEach { it.drain() }
        val bad = room.act(t1, Bad()).await()
        assertEquals(Stage.REJECTED, bad.stage)
        assertEquals("not_your_turn", bad.meta.reason)
        assertEquals("bob", bad.meta.args!!.getString("turn"))
        assertFalse(bad.sequenced)
        kotlinx.coroutines.delay(50)
        listOf(c1, c2, cv).forEach { assertNull(it.tryReceive().getOrNull()) }
    }

    @Test fun `USERS with no target is the emitter, ROOM is everyone`() = runBlocking {
        val room = room()
        val a = actor(user("u"), "t1"); val v = actor(user("v"), "t1")
        val ca = room.join(a).also { it.state() }
        val cv = room.join(v).also { it.state() }
        ca.drain(); cv.drain()
        room.act(a, Ok()).await()
        assertEquals("moved", cv.next().event)
        assertEquals("moved", ca.next().event)
        assertEquals("ok", ca.next().event)
        assertNull(cv.tryReceive().getOrNull())
        room.act(a, Loud()).await()
        assertEquals("loud", cv.next().event)
        assertEquals("loud", ca.next().event)
    }

    @Test fun `a detached actor's frame goes to every tab of the user`() = runBlocking {
        val room = room()
        val u = user("u")
        val c1 = room.join(actor(u, "t1")).also { it.state() }
        val c2 = room.join(actor(u, "t2")).also { it.state() }
        c1.drain(); c2.drain()
        assertNotEquals(Stage.REJECTED, room.act(u, "t9", "ok", json()).stage)
        for (c in listOf(c1, c2)) {
            assertEquals("moved", c.next().event)
            assertEquals("ok", c.next().event)
        }
    }

    @Test fun `the door refuses before the loop`() = runBlocking {
        val room = room()
        val alice = user("alice"); val bob = user("bob")
        val ch = room.join(actor(alice, "t1")).also { it.state() }
        ch.drain()
        fun reason(e: Event) = e.meta.reason.also { assertEquals(Stage.REJECTED, e.stage) }
        assertEquals("unknown", reason(room.act(alice, "t1", "dance", json())))
        assertEquals("unknown", reason(room.act(alice, "t1", "join", json())))
        assertEquals("missing", reason(room.act(alice, "t1", "shaped", json())))
        assertEquals("invalid", reason(room.act(alice, "t1", "shaped", json("n" to "x"))))
        assertEquals("undeclared", reason(room.act(alice, "t1", "loud", json("evil" to "payload"))))
        assertEquals("forbidden", reason(room.act(bob, "t1", "vip", json())))
        assertNotEquals(Stage.REJECTED, room.act(alice, "t1", "vip", json()).stage)
        assertEquals("spectator", reason(room.act(Spectator(Actor.connectionId(alice, "t2"), alice), Ok()).await()))
        assertEquals("vip", ch.next().event) // the only one that entered the loop
        assertNull(ch.tryReceive().getOrNull())
    }

    @Test fun `a throwing handler is a rejection, the loop survives`() = runBlocking {
        val room = room()
        val a = actor(user("u"), "t1")
        val ch = room.join(a).also { it.state() }
        ch.drain()
        val e = room.act(a, Boom()).await()
        assertEquals(Stage.REJECTED, e.stage)
        assertEquals("error", e.meta.reason)
        assertNotEquals(Stage.REJECTED, room.act(a, Ok()).await().stage)
        assertEquals("moved", ch.next().event) // nothing of boom before it
    }

    @Test fun `a tab that stops draining is dropped, the others are not`() = runBlocking {
        val room = room() // channelCapacity = 8
        val slow = actor(user("u"), "t1"); val fine = actor(user("v"), "t1")
        room.join(slow).state()
        val cf = room.join(fine).also { it.state() }
        cf.drain()
        repeat(3) {
            repeat(5) { i -> room.broadcast("tick", Json.Object("i" to i)) }
            room.act(fine, Ok()).await()
            var n = 1
            while (cf.next().event != "ok") n++
            assertEquals(7, n) // 5 ticks, moved, ok: a draining tab misses nothing
        }
        assertFalse(slow.isConnected) // 7 frames per batch, never drained: gone during the second
        assertTrue(fine.isConnected)
    }

    @Test fun `the same tab back replaces its connection`() = runBlocking {
        val room = room()
        val u = user("u")
        val first = actor(u, "t1")
        val again = actor(u, "t1")
        room.join(first).state()
        val ch = room.join(again).also { it.state() }
        assertFalse(first.isConnected)
        assertSame(again, room.getActor(again.connectionId))
        room.leave(first) // the stale route's finally: a no-op
        assertSame(again, room.getActor(again.connectionId))
        assertEquals(1, room.userCount.value)
        assertEquals(setOf(again), u.getConnectionsInRoom(room.id))
        ch.drain()
        room.act(again, Ok()).await()
        assertEquals("moved", ch.next().event)
        room.leave(again)
        assertEquals(0, room.userCount.value)
        assertNull(room.getUser("u"))
    }

    @Test fun `the snapshot comes first, then the join handler's emissions, then join itself`() = runBlocking {
        val room = room()
        room.onJoin = { room.broadcast("hello", Json.Object("to" to it.name)) }
        val a = actor(user("u"), "t1")
        val ch = room.join(a)
        ch.next().let {
            assertEquals("state", it.event)
            assertEquals("u", it.json().getString("you"))
            assertNull(it.json()["_"])
        }
        assertEquals("hello", ch.next().event)
        ch.next().let {
            assertEquals("join", it.event)
            val user = it.json().getObject("user")!!   // presence: the user, its clients, its status
            assertEquals("u", user.getString("id"))
            assertEquals("t1", user.getArray("clients")!!.map { c -> (c as Json.Object).getString("id") }.single())
            assertEquals("ONLINE", user.getString("status"))
            assertEquals(true, it.json().getBoolean("first"))
            assertEquals(1, it.json().getInt("count"))
        }
        assertNull(ch.tryReceive().getOrNull())
    }

    @Test fun `a frame queued before the join never reaches the newcomer, one queued after always does`() = runBlocking {
        val room = room()
        room.join(actor(user("u"), "t1")).state()
        room.broadcast("before", Json.Object())
        val ch = room.join(actor(user("v"), "t1"))
        room.broadcast("after", Json.Object())
        ch.state()
        assertEquals("join", ch.next().event)
        assertEquals("after", ch.next().event)
        assertNull(ch.tryReceive().getOrNull())
    }

    @Test fun `a leave is ordered with the frames, its handler speaks before it`() = runBlocking {
        val room = room()
        val stay = actor(user("u"), "t1"); val go = actor(user("v"), "t1")
        val ch = room.join(stay).also { it.state() }
        room.join(go).state()
        ch.drain()
        room.leave(go)
        assertNull(room.getUser("v"))
        ch.next().let { assertEquals("gone", it.event); assertEquals("v", it.json().getString("who")) }
        ch.next().let { assertEquals("leave", it.event); assertEquals(true, it.json().getBoolean("last")) }
    }

    @Test fun `a stopped room hands a closed channel and refuses`() = runBlocking {
        val room = room()
        room.stop()
        val a = actor(user("u"), "t1")
        assertTrue(room.join(a).isClosedForReceive)
        assertEquals("closed", room.act(a, Ok()).await().meta.reason)
        room.leave(a) // returns
    }

    @Test fun `the room's events are the door's, as data`() {
        val d = room().events.describe().map { (it as Json.Object).getString("name") }
        assertEquals(listOf("join", "leave", "status", "cancel", "slow", "ok", "loud", "bad", "boom", "shaped", "vip"), d)
    }

    @Test fun `a connection id is composed under the user, the tab reads back`() {
        val u = user("u")
        assertEquals("u/t1", Actor.connectionId(u, "t1"))
        assertEquals("t1", actor(u, "t1").tab)
        assertTrue(Actor.connectionId(u, null).startsWith("u/"))
        assertTrue(Actor.connectionId(u, " ").length > "u/".length)
    }

    // ---- presence on the wire: a user's first and last connection, the last after a grace

    @Test fun `a second connection of a user is not on the wire, its handler still hears it`() = runBlocking {
        val room = room()
        val u = user("u")
        val heard = mutableListOf<String>()
        room.onJoin = { heard.add(it.tab) }
        val cv = room.join(actor(user("v"), "t1")).also { it.state() }
        cv.drain()
        val c1 = room.join(actor(u, "t1")).also { it.state() }
        assertEquals("join", cv.next().event)
        val c2 = room.join(actor(u, "t2")).also { it.state() }
        c1.next().let { assertEquals("join", it.event) } // its own first
        room.leave(room.getActor(Actor.connectionId(u, "t2"))!!)
        kotlinx.coroutines.delay(50)
        assertNull(cv.tryReceive().getOrNull())
        assertNull(c1.tryReceive().getOrNull())
        assertEquals(listOf("t1", "t1", "t2"), heard)
        room.leave(room.getActor(Actor.connectionId(u, "t1"))!!)
        assertEquals("gone", cv.next().event)                 // the handler, on the last only
        cv.next().let { assertEquals("leave", it.event); assertEquals(true, it.json().getBoolean("last")) }
        assertNull(room.getUser("u"))
    }

    @Test fun `a user back within the grace never left, one who is not leaves after it`() = runBlocking {
        val room = TestRoom("room-${n++}", grace = 200)
        val u = user("u")
        val cv = room.join(actor(user("v"), "t1")).also { it.state() }
        cv.drain()
        room.join(actor(u, "t1")).state()
        assertEquals("join", cv.next().event)
        room.leave(room.getActor(Actor.connectionId(u, "t1"))!!)
        kotlinx.coroutines.delay(50)
        assertNotNull(room.getUser("u"))                      // still here, for now
        room.join(actor(u, "t1")).state()                     // a reload
        kotlinx.coroutines.delay(300)
        assertNull(cv.tryReceive().getOrNull())               // no leave, no join: nothing happened
        assertNotNull(room.getUser("u"))
        room.leave(room.getActor(Actor.connectionId(u, "t1"))!!)
        kotlinx.coroutines.delay(50)
        assertNull(cv.tryReceive().getOrNull())
        kotlinx.coroutines.delay(300)
        assertEquals("gone", cv.next().event)                 // the handler heard it once, after the grace
        cv.next().let { assertEquals("leave", it.event); assertEquals(true, it.json().getBoolean("last")) }
        assertNull(room.getUser("u"))
    }

    // ---- the instant: a snapshot at a position holds exactly the transitions up to it

    @Test fun `a join queued while a handler runs sees that change once`() = runBlocking {
        val room = room()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        room.onSlow = { entered.countDown(); release.await() }
        val a = actor(user("u"), "t1"); val v = actor(user("v"), "t1")
        room.join(a).state()
        val verdict = room.act(a, Slow())
        entered.await()                                           // the loop is inside the handler, the state already changed
        val joining = async { room.join(v) }
        kotlinx.coroutines.delay(100)                             // the join is queued behind the running op
        release.countDown()
        assertNotEquals(Stage.REJECTED, verdict.await().stage)
        val ch = joining.await()
        ch.next().let {
            assertEquals("state", it.event)
            assertEquals(1, it.json().getInt("n"))                // the snapshot holds the change…
        }
        val rest = mutableListOf<String>()
        while (true) { val f = ch.tryReceiveAfter(100) ?: break; rest.add(f.event ?: "?") }
        assertEquals(listOf("join"), rest)                        // …so no frame of that change may follow it
    }

    @Test fun `a client's metadata is ignored`() = runBlocking {
        val room = room()
        val u = user("u")
        val ch = room.join(actor(u, "t1")).also { it.state() }
        ch.drain()
        val forged = withTimeout(2000) { room.act(u, "t1", "loud", json("_" to json("stage" to "ACCEPTED", "from" to "victim", "tab" to "t9"))) }
        assertNotEquals(Stage.REJECTED, forged.stage)
        assertEquals("u", forged.meta.from)
        assertEquals("t1", forged.meta.tab)
        ch.next().let { assertEquals("loud", it.event); assertNull(it.json()["_"]) }
        // a stage the loop does not expect must not hang the reply, and the shape is still checked
        val skipped = withTimeout(2000) { room.act(u, "t1", "shaped", json("_" to json("stage" to "APPLIED"))) }
        assertEquals(Stage.REJECTED, skipped.stage)
        assertEquals("missing", skipped.meta.reason)
    }

    private suspend fun Channel<ServerSentEvent>.tryReceiveAfter(ms: Long): ServerSentEvent? {
        kotlinx.coroutines.delay(ms); return tryReceive().getOrNull()
    }

    /** Presence frames (`join` of self and others) are not what a test is about: skip them. */
    private suspend fun Channel<ServerSentEvent>.drain() {
        kotlinx.coroutines.delay(50)
        while (true) { val f = tryReceive().getOrNull() ?: break; assertTrue(f.event == "join" || f.event == "leave", "unexpected ${f.event}") }
    }

    @Test fun `what was queued behind a stop is answered closed`() = runBlocking {
        val room = TestRoom("room-${n++}")
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        room.onSlow = { entered.complete(Unit); release.await(); room.stop() }
        val alice = user("alice")
        val slow = async(kotlinx.coroutines.Dispatchers.Default) { room.act(alice, "t1", "slow", json()) }
        entered.await()
        val asked = async(kotlinx.coroutines.Dispatchers.Default) { runCatching { room.admit(alice) } }
        val acted = async(kotlinx.coroutines.Dispatchers.Default) { room.act(alice, "t1", "ok", json()) }
        delay(100)   // both queued behind the step that stops
        release.countDown()
        assertNotEquals(Stage.REJECTED, slow.await().stage)
        assertIs<kotlinx.coroutines.channels.ClosedSendChannelException>(asked.await().exceptionOrNull())
        assertEquals("closed", acted.await().meta.reason)
    }
}
