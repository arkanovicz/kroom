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

    private class TestRoom(id: String, text: String = "", override val leaveGrace: Long = 0) : LogicRoom<TextState>(id, TextState(text), TextLogic()) {
        override fun shouldCloseWhenEmpty() = false
        val text get() = state
    }

    private var n = 0
    private fun user(id: String) = Users.getOrCreate(id, id)
    private fun actor(user: User, tab: String) = Actor(Actor.connectionId(user, tab), user)

    private suspend fun Channel<ServerSentEvent>.next(): ServerSentEvent = withTimeout(2000) { receive() }
    private suspend fun Channel<ServerSentEvent>.state(): Json.Object = next().also { assertEquals("state", it.event) }.json()
    private suspend fun Channel<ServerSentEvent>.silent() { delay(50); assertNull(tryReceive().getOrNull()) }
    private fun Channel<ServerSentEvent>.drain() { while (tryReceive().isSuccess) { /* past */ } }
    private fun ServerSentEvent.json() = Json.parse(data!!) as Json.Object
    private fun Json.Object.parts() = getArray("parts")!!.map { it as Json.Object }

    private suspend fun TestRoom.take(who: Actor, vararg ids: String) = act(who, Take(Json.MutableObject("ids" to Json.MutableArray(ids.toList())))).await()
    private suspend fun TestRoom.write(who: Actor, id: String, text: String) = act(who, Write(Json.MutableObject("id" to id, "text" to text))).await()
    private suspend fun TestRoom.after(who: Actor, id: String) = act(who, Take(Json.MutableObject("after" to id))).await()
    private suspend fun TestRoom.release(who: Actor, id: String) = act(who, Release(id)).await()

    @BeforeTest fun clean() = Users.clear()

    @Test fun `a take, a write and a release are frames that say what was done, the snapshot is their fold`() = runBlocking {
        val room = TestRoom("text-${n++}", "One.\n\nTwo.\n\nThree.")
        val alice = actor(user("alice"), "t1")
        val ca = room.join(alice).also { it.state() }
        ca.drain()
        assertNotEquals(Stage.REJECTED, room.take(alice, "1", "2").stage)
        ca.next().let {
            assertEquals("take", it.event)
            assertEquals("""{"ids":["1","2"],"user":"alice","client":"t1"}""", it.data)   // who holds is the frame's: a fold needs no metadata
        }
        room.write(alice, "1", "One.\n\nOne and a half.\n\nTwo.")
        ca.next().let { assertEquals("write", it.event); assertEquals("""{"id":"1","text":"One.\n\nOne and a half.\n\nTwo."}""", it.data) }
        val late = room.join(actor(user("bob"), "t1")).state()
        assertEquals(listOf("1", "3"), late.parts().map { it.getString("id") })           // held, a part is one, whatever it says
        assertEquals(listOf("alice", null), late.parts().map { it.getString("holder") })
        room.release(alice, "1")
        ca.drain()
        assertEquals("""{"id":"1"}""", Release("1").json.toString())                        // and a release names the part, nothing more
        assertEquals(listOf("1", "4", "5", "3"), room.text.parts.map { it.id })             // cut by the fold, on both sides alike
        assertEquals("One.\n\nOne and a half.\n\nTwo.\n\nThree.", room.text.text)
        assertTrue(room.text.parts.all { it.free })
    }

    @Test fun `two writes in a row say the same part twice, nothing doubles`() = runBlocking {
        val room = TestRoom("text-${n++}")
        val alice = actor(user("alice"), "t1")
        room.join(alice).state()
        room.take(alice, "1")
        val first = room.act(alice, Write(Json.MutableObject("id" to "1", "text" to "A\n\nB")))   // not awaited: the page did not wait either
        room.write(alice, "1", "A\n\nB\n\nC")
        first.await()
        room.release(alice, "1")
        assertEquals("A\n\nB\n\nC", room.text.text)
        assertEquals(3, room.text.parts.size)
    }

    @Test fun `a refusal is the verdict alone`() = runBlocking {
        val room = TestRoom("text-${n++}", "One.\n\nTwo.")
        val alice = actor(user("alice"), "t1"); val bob = actor(user("bob"), "t1")
        room.join(alice).state()
        val cb = room.join(bob).also { it.state() }
        room.take(alice, "1")
        cb.drain()
        room.take(bob, "1", "2").let { assertEquals(Stage.REJECTED, it.stage); assertEquals("held", it.meta.reason); assertEquals("alice", it.meta.args?.getString("by")) }
        room.write(bob, "1", "mine").let { assertEquals("not_held", it.meta.reason) }
        cb.silent()
        assertEquals("One.\n\nTwo.", room.text.text)
        assertNotEquals(Stage.REJECTED, room.take(alice, "2").stage)                        // elsewhere: what she held is given back
        assertTrue(room.text.parts["1"]!!.free)
        assertEquals("alice", room.text.parts["2"]!!.holder)
        assertNotEquals(Stage.REJECTED, room.take(bob, "1").stage)                          // each in a part of their own
    }

    @Test fun `going from one paragraph to the next is one frame`() = runBlocking {
        val room = TestRoom("text-${n++}", "One.\n\nTwo.")
        val alice = actor(user("alice"), "t1")
        room.join(alice).state()
        val watch = room.join(actor(user("bob"), "t1")).also { it.state() }
        room.take(alice, "1")
        room.write(alice, "1", "One.\n\nOne and a half.")
        watch.drain()
        assertNotEquals(Stage.REJECTED, room.take(alice, "2").stage)
        watch.next().let { assertEquals("take", it.event); assertEquals("""{"ids":["2"],"user":"alice","client":"t1"}""", it.data) }
        watch.silent()                                                                      // no release frame: the take is the release
        assertEquals(listOf("1", "3", "2"), room.text.parts.map { it.id })
        assertEquals(listOf(null, null, "alice"), room.text.parts.map { it.holder })
        assertEquals("One.\n\nOne and a half.\n\nTwo.", room.text.text)
    }

    @Test fun `a paragraph begun is a part born after, held`() = runBlocking {
        val room = TestRoom("text-${n++}", "One.\n\nTwo.")
        val alice = actor(user("alice"), "t1")
        val watch = room.join(alice).also { it.state() }
        watch.drain()
        assertNotEquals(Stage.REJECTED, room.after(alice, "1").stage)
        watch.next().let { assertEquals("take", it.event); assertEquals("""{"after":"1","user":"alice","client":"t1"}""", it.data) }
        assertEquals(listOf("1", "3", "2"), room.text.parts.map { it.id })
        assertEquals(listOf(null, "alice", null), room.text.parts.map { it.holder })
        room.after(alice, "9").let { assertEquals("gone", it.meta.reason) }
    }

    @Test fun `a write's frame carries the caret`() = runBlocking {
        val room = TestRoom("text-${n++}")
        val alice = actor(user("alice"), "t1")
        val watch = room.join(alice).also { it.state() }
        room.take(alice, "1")
        watch.drain()
        assertNotEquals(Stage.REJECTED, room.act(alice, Write(Json.MutableObject("id" to "1", "text" to "One", "caret" to 3))).await().stage)
        watch.next().let { assertEquals("write", it.event); assertEquals("""{"id":"1","text":"One","caret":3}""", it.data) }
        assertEquals(3, room.text.parts["1"]!!.caret)
    }

    @Test fun `a tab closed beside another gives back what it held, the other keeps its own`() = runBlocking {
        val room = TestRoom("text-${n++}", "One.\n\nTwo.")
        val alice = user("alice")
        val t1 = actor(alice, "t1"); val t2 = actor(alice, "t2")
        val watch = room.join(actor(user("bob"), "t1")).also { it.state() }
        room.join(t1).state()
        room.join(t2).state()
        room.take(t1, "1")
        room.take(t2, "1").let { assertEquals("held", it.meta.reason) }   // the same user, another tab: not its own
        room.take(t2, "2")
        watch.drain()
        room.leave(t1)
        watch.next().let { assertEquals("release", it.event); assertEquals("""{"id":"1"}""", it.data) }   // no leave: alice is still here
        watch.silent()
        assertTrue(room.text.parts["1"]!!.free)
        assertEquals("t2", room.text.parts["2"]!!.client)
        room.leave(t2)
        assertEquals("leave", watch.next().event)                          // the user's last: the leave, then what it held
        watch.next().let { assertEquals("release", it.event); assertEquals("""{"id":"2"}""", it.data) }
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
        assertNotEquals(Stage.REJECTED, room.write(back, "1", "Still mine.").stage)
        assertEquals("write", watch.next().event)
        room.leave(back)
        delay(50)
        assertFalse(room.text.parts["1"]!!.free)                           // not yet
        assertEquals("release", watch.next().event)                        // gone for good: after the grace
        assertTrue(room.text.parts["1"]!!.free)
    }
}
