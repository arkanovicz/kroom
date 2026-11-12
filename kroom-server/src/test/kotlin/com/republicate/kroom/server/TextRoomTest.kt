package com.republicate.kroom.server

import com.republicate.kroom.event.Release
import com.republicate.kroom.event.Stage
import com.republicate.kroom.event.Take
import com.republicate.kroom.event.TextLogic
import com.republicate.kroom.event.TextState
import com.republicate.kroom.event.Write
import com.republicate.kson.Json
import io.ktor.sse.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** Pins a text in a room: a plain [LogicRoom] over [TextState] and [TextLogic], a part held by a tab for as long as the tab is there. */
class TextRoomTest {

    private class TestRoom(id: String, override val leaveGrace: Long = 0) : LogicRoom<TextState>(id, TextState(), TextLogic()) {
        override fun shouldCloseWhenEmpty() = false
        val text get() = state
    }

    private var n = 0
    private fun user(id: String) = Users.getOrCreate(id, id)
    private fun actor(user: User, tab: String) = Actor(Actor.connectionId(user, tab), user)
    private fun ids(vararg ids: String) = Json.MutableArray(ids.toList())

    private suspend fun Channel<ServerSentEvent>.next(): ServerSentEvent = withTimeout(2000) { receive() }
    private suspend fun Channel<ServerSentEvent>.state(): Json.Object = next().also { assertEquals("state", it.event) }.json()
    private suspend fun Channel<ServerSentEvent>.silent() { delay(50); assertNull(tryReceive().getOrNull()) }
    private fun Channel<ServerSentEvent>.drain() { while (tryReceive().isSuccess) { /* past */ } }
    private fun ServerSentEvent.json() = Json.parse(data!!) as Json.Object
    private fun Json.Object.parts() = getArray("parts")!!.map { it as Json.Object }

    private suspend fun TestRoom.take(who: Actor, vararg ids: String) = act(who, Take(Json.MutableObject("ids" to ids(*ids)))).await()
    private suspend fun TestRoom.write(who: Actor, ids: Json.Array, vararg texts: String) =
        act(who, Write(Json.MutableObject("ids" to ids, "texts" to Json.MutableArray(texts.toList())))).await()

    @BeforeTest fun clean() = Users.clear()

    @Test fun `a take, a write and a release are frames, the snapshot is their fold`() = runBlocking {
        val room = TestRoom("text-${n++}")
        val alice = actor(user("alice"), "t1")
        val ca = room.join(alice).also { it.state() }
        ca.drain()
        assertNotEquals(Stage.REJECTED, room.take(alice, "1").stage)
        ca.next().let {
            assertEquals("take", it.event)
            assertEquals("""{"ids":["1"],"user":"alice","client":"t1"}""", it.data)   // who holds is the frame's: a fold needs no metadata
        }
        room.write(alice, ids("1"), "One.", "Two.")
        ca.next().let { assertEquals("write", it.event); assertEquals("""{"ids":["1"],"texts":["One.","Two."],"born":["2"]}""", it.data) }
        val late = room.join(actor(user("bob"), "t1")).state()
        assertEquals(listOf("One.", "Two."), late.parts().map { it.getString("text") })
        assertEquals(listOf("alice", "alice"), late.parts().map { it.getString("holder") })
        assertNotEquals(Stage.REJECTED, room.act(alice, Release(listOf("1", "2"))).await().stage)
        assertEquals("One.\n\nTwo.", room.text.text)
        assertTrue(room.text.parts.all { it.free })
    }

    @Test fun `a refusal is the verdict alone`() = runBlocking {
        val room = TestRoom("text-${n++}")
        val alice = actor(user("alice"), "t1"); val bob = actor(user("bob"), "t1")
        room.join(alice).state()
        val cb = room.join(bob).also { it.state() }
        room.take(alice, "1")
        cb.drain()
        room.take(bob, "1").let { assertEquals(Stage.REJECTED, it.stage); assertEquals("held", it.meta.reason); assertEquals("alice", it.meta.args?.getString("by")) }
        room.write(bob, ids("1"), "mine").let { assertEquals("not_held", it.meta.reason) }
        cb.silent()
        assertEquals("", room.text.text)
    }

    @Test fun `a tab closed beside another gives back what it held, the other keeps its own`() = runBlocking {
        val room = TestRoom("text-${n++}")
        val alice = user("alice")
        val t1 = actor(alice, "t1"); val t2 = actor(alice, "t2")
        val watch = room.join(actor(user("bob"), "t1")).also { it.state() }
        room.join(t1).state()
        room.join(t2).state()
        room.take(t1, "1")
        room.write(t1, ids("1"), "One.", "Two.")
        room.act(t1, Release(listOf("2"))).await()
        room.take(t2, "2")
        room.take(t2, "1").let { assertEquals("held", it.meta.reason) }   // the same user, another tab: not its own
        watch.drain()
        room.leave(t1)
        watch.next().let { assertEquals("release", it.event); assertEquals("""{"ids":["1"]}""", it.data) }   // no leave: alice is still here
        watch.silent()
        assertTrue(room.text.parts["1"]!!.free)
        assertEquals("t2", room.text.parts["2"]!!.client)
        room.leave(t2)
        assertEquals("leave", watch.next().event)                          // the user's last: the leave, then what it held
        watch.next().let { assertEquals("release", it.event); assertEquals("""{"ids":["2"]}""", it.data) }
    }

    @Test fun `a tab reloaded within the grace keeps what it holds`() = runBlocking {
        val room = TestRoom("text-${n++}", leaveGrace = 150)
        val alice = user("alice")
        val watch = room.join(actor(user("bob"), "t1")).also { it.state() }
        val t1 = actor(alice, "t1")
        room.join(t1).state()
        room.join(actor(alice, "t2")).state()
        room.take(t1, "1")
        watch.drain()
        room.leave(t1)
        delay(50)
        val back = actor(alice, "t1")
        assertEquals("alice", room.join(back).state().parts().single().getString("holder"))   // its own, still: the page takes its text up again
        delay(250)
        assertNull(watch.tryReceive().getOrNull())
        assertNotEquals(Stage.REJECTED, room.write(back, ids("1"), "Still mine.").stage)
        assertEquals("write", watch.next().event)
        room.leave(back)
        delay(50)
        assertFalse(room.text.parts["1"]!!.free)                           // not yet
        assertEquals("release", watch.next().event)                        // gone for good: after the grace
        assertTrue(room.text.parts["1"]!!.free)
    }
}
