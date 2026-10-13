package com.republicate.kroom.server

import com.republicate.kson.Json
import io.ktor.sse.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** Pins the intent contract (private/intents.md): act on the loop, feedback after effects, trySend, stable tabs. */
class RoomTest {

    private class TestRoom(id: String) : Room<Json.MutableObject>(id) {
        override var state = Json.MutableObject()
        override fun shouldCloseWhenEmpty() = false
        override val channelCapacity = 8
        override fun stateToJson(): Json.Object = Json.Object()
        override fun stateToJson(actor: Actor): Json.Object = Json.Object("you" to actor.name)
        var onJoin: (Actor) -> Unit = {}
        override suspend fun onActorJoined(actor: Actor) = onJoin(actor)
        override suspend fun onActorLeft(actor: Actor, userFullyLeft: Boolean) { if (userFullyLeft) broadcast("gone", Json.Object("who" to actor.name)) }
        override fun handleAction(actor: Actor, action: Json.Object): ActionResult = when (action.getString("type")) {
            "ok" -> { broadcast("moved", Json.Object("by" to actor.name)); ActionResult.Success() }
            "emit" -> ActionResult.Success("done", Json.Object("x" to 1))
            "boom" -> throw IllegalStateException("kaboom")
            else -> ActionResult.Error("not your turn")
        }
    }

    private var n = 0
    private fun room() = TestRoom("room-${n++}")
    private fun user(id: String) = Users.getOrCreate(id, id)
    private fun actor(user: User, tab: String) = Actor(Actor.connectionId(user, tab), user)
    private fun intent(type: String) = Json.Object("type" to type)

    private suspend fun Channel<ServerSentEvent>.next(): ServerSentEvent = withTimeout(2000) { receive() }
    private suspend fun Channel<ServerSentEvent>.state() = assertEquals("state", next().event)

    @BeforeTest fun clean() = Users.clear()

    @Test fun `accepted lands after the handler's own broadcast`() = runBlocking {
        val room = room()
        val a = actor(user("u"), "t1")
        val ch = room.join(a).also { it.state() }
        val verdict = room.act(a, intent("ok")).await()
        assertEquals(ActionResult.Success(), verdict)
        assertEquals("moved", ch.next().event)
        ch.next().let { assertEquals("accepted", it.event); assertEquals("""{"intent":"ok"}""", it.data) }
    }

    @Test fun `rejected reaches the acting tab only`() = runBlocking {
        val room = room()
        val u = user("u")
        val t1 = actor(u, "t1"); val t2 = actor(u, "t2"); val v = actor(user("v"), "t1")
        val c1 = room.join(t1).also { it.state() }
        val c2 = room.join(t2).also { it.state() }
        val cv = room.join(v).also { it.state() }
        assertEquals(ActionResult.Error("not your turn"), room.act(t1, intent("bad")).await())
        c1.next().let { assertEquals("rejected", it.event); assertEquals("""{"intent":"bad","reason":"not your turn"}""", it.data) }
        assertNull(c2.tryReceive().getOrNull())
        assertNull(cv.tryReceive().getOrNull())
    }

    @Test fun `a detached actor's feedback goes to every tab of the user`() = runBlocking {
        val room = room()
        val u = user("u")
        val c1 = room.join(actor(u, "t1")).also { it.state() }
        val c2 = room.join(actor(u, "t2")).also { it.state() }
        assertEquals(ActionResult.Success(), room.act(u, "t9", intent("ok")))
        for (c in listOf(c1, c2)) {
            assertEquals("moved", c.next().event)
            assertEquals("accepted", c.next().event)
        }
    }

    @Test fun `a success carrying an event broadcasts it before accepted`() = runBlocking {
        val room = room()
        val a = actor(user("u"), "t1")
        val ch = room.join(a).also { it.state() }
        room.act(a, intent("emit")).await()
        ch.next().let { assertEquals("done", it.event); assertEquals("""{"x":1}""", it.data) }
        assertEquals("accepted", ch.next().event)
    }

    @Test fun `a throwing handler is a rejection, the loop survives`() = runBlocking {
        val room = room()
        val a = actor(user("u"), "t1")
        val ch = room.join(a).also { it.state() }
        assertEquals(ActionResult.Error("kaboom"), room.act(a, intent("boom")).await())
        assertEquals("rejected", ch.next().event)
        assertEquals(ActionResult.Success(), room.act(a, intent("ok")).await())
    }

    @Test fun `spectators cannot act`() = runBlocking {
        val room = room()
        val s = Spectator(Actor.connectionId(user("u"), "t1"), user("u"))
        val ch = room.joinAsSpectator(s).also { it.state() }
        assertEquals(ActionResult.Error("spectators cannot act"), room.act(s, intent("ok")).await())
        assertNull(ch.tryReceive().getOrNull())
    }

    @Test fun `a tab that stops draining is dropped, the others are not`() = runBlocking {
        val room = room() // channelCapacity = 8
        val slow = actor(user("u"), "t1"); val fine = actor(user("v"), "t1")
        room.join(slow).state()
        val cf = room.join(fine).also { it.state() }
        repeat(3) {
            repeat(5) { i -> room.broadcast("tick", Json.Object("i" to i)) }
            room.act(fine, intent("ok")).await()
            var n = 1
            while (cf.next().event != "accepted") n++
            assertEquals(7, n) // 5 ticks, moved, accepted: a draining tab misses nothing
        }
        assertFalse(slow.isConnected) // 6 frames per batch, never drained: gone during the second
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
        room.act(again, intent("ok")).await()
        assertEquals("moved", ch.next().event)
        room.leave(again)
        assertEquals(0, room.userCount.value)
        assertNull(room.getUser("u"))
    }

    @Test fun `the snapshot comes first, the join hook's broadcasts after it, and it is the viewer's`() = runBlocking {
        val room = room()
        room.onJoin = { room.broadcast("hello", Json.Object("to" to it.name)); room.sendTo(it, "psst", Json.Object()) }
        val a = actor(user("u"), "t1")
        val ch = room.join(a)
        ch.next().let { assertEquals("state", it.event); assertEquals("""{"you":"u"}""", it.data) }
        assertEquals("hello", ch.next().event)
        assertEquals("psst", ch.next().event)
        assertNull(ch.tryReceive().getOrNull())
    }

    @Test fun `a frame queued before the join never reaches the newcomer, one queued after always does`() = runBlocking {
        val room = room()
        val first = actor(user("u"), "t1")
        room.join(first).state()
        val a = actor(user("v"), "t1")
        room.broadcast("before", Json.Object())
        val ch = room.join(a)
        room.broadcast("after", Json.Object())
        ch.state()
        assertEquals("after", ch.next().event)
        assertNull(ch.tryReceive().getOrNull())
    }

    @Test fun `a leave is ordered with the frames, its hook speaks after it`() = runBlocking {
        val room = room()
        val stay = actor(user("u"), "t1"); val go = actor(user("v"), "t1")
        val ch = room.join(stay).also { it.state() }
        room.join(go).state()
        room.leave(go)
        assertNull(room.getUser("v"))
        ch.next().let { assertEquals("gone", it.event); assertEquals("""{"who":"v"}""", it.data) }
    }

    @Test fun `a stopped room hands a closed channel`() = runBlocking {
        val room = room()
        room.stop()
        val ch = room.join(actor(user("u"), "t1"))
        assertTrue(ch.isClosedForReceive)
        room.leave(actor(user("u"), "t1")) // returns
    }

    @Test fun `a connection id is composed under the user`() {
        val u = user("u")
        assertEquals("u/t1", Actor.connectionId(u, "t1"))
        assertTrue(Actor.connectionId(u, null).startsWith("u/"))
        assertTrue(Actor.connectionId(u, " ").length > "u/".length)
    }
}
