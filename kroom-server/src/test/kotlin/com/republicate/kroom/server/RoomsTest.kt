package com.republicate.kroom.server

import com.republicate.kson.Json
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Pins rooms on demand: built once under the lock, closed once idle, forgotten, built anew. */
class RoomsTest {
    private var n = 0

    private class Quick(id: String) : Room<Json.MutableObject>(id) {
        override var state = Json.MutableObject()
        override val idleTimeout get() = 100L
        override val leaveGrace get() = 0L
        override fun stateToJson(): Json.Object = Json.Object()
    }

    private suspend fun gone(room: Room<*>) = withTimeout(2000) { while (room.running) delay(10) }

    @Test fun `two first calls build one room`() = runBlocking {
        val id = "lobby-${n++}"
        val builds = AtomicInteger()
        val rooms = (1..8).map { async { Rooms.getOrCreate(id) { delay(50); builds.incrementAndGet(); Quick(it) } } }.awaitAll()
        assertEquals(1, builds.get())
        assertEquals(1, rooms.toSet().size)
        assertNull(Rooms.getOrCreate("lobby-${n++}") { null })   // no such room
        rooms.first()!!.stop()
    }

    @Test fun `a room built and never joined closes, the next call builds it anew`() = runBlocking {
        val id = "lobby-${n++}"
        val first = Rooms.getOrCreate(id) { Quick(it) }!!
        gone(first)
        assertFalse(Rooms.exists(id))
        val second = Rooms.getOrCreate(id) { Quick(it) }!!
        assertNotSame(first, second)
        assertTrue(second.running)
        second.stop()
    }

    @Test fun `an occupied room stays, an emptied one closes after the timeout counted from its last leave`() = runBlocking {
        val id = "lobby-${n++}"
        val room = Rooms.getOrCreate(id) { Quick(it) }!!
        val user = Users.getOrCreate("lobby-u", "U")
        val tab1 = Actor(Actor.connectionId(user, "t1"), user)
        room.join(tab1)
        delay(250)
        assertTrue(room.running)
        room.leave(tab1)
        delay(70)
        val tab2 = Actor(Actor.connectionId(user, "t2"), user)
        room.join(tab2); room.leave(tab2)   // back and gone again: the clock restarts
        delay(60)
        assertTrue(room.running)            // 130 ms after the first leave, 60 after the last
        gone(room)
        assertFalse(Rooms.exists(id))
    }

    @Test fun `a room the host built lives with the app`() = runBlocking {
        val room = Rooms.register(Quick("lobby-${n++}"))
        val user = Users.getOrCreate("lobby-v", "V")
        val tab = Actor(Actor.connectionId(user, "t1"), user)
        room.join(tab); room.leave(tab)
        delay(300)
        assertTrue(room.running)
        room.stop()
    }
}
