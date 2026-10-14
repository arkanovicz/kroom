package com.republicate.kroom.server

import com.republicate.kroom.event.Stage
import com.republicate.kson.Json
import io.ktor.sse.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** Pins the chat contract: the room numbers, the store keeps, the loop never waits. */
class ChatRoomTest {

    private var n = 0
    private fun user(id: String) = Users.getOrCreate(id, id)
    private fun actor(user: User) = Actor(Actor.connectionId(user, "t1"), user)
    private suspend fun Channel<ServerSentEvent>.next(): ServerSentEvent = withTimeout(2000) { receive() }
    private suspend fun Channel<ServerSentEvent>.until(name: String): ServerSentEvent { while (true) { val f = next(); if (f.event == name) return f } }
    private suspend fun ChatHistory.settled(seq: Long) = withTimeout(2000) { while (tail(1).lastOrNull()?.seq != seq) kotlinx.coroutines.yield() }

    @BeforeTest fun clean() = Users.clear()

    @Test fun `a message is numbered, emitted, then kept`() = runBlocking {
        val store = MemoryChatHistory()
        val room = ChatRoom("chat-${n++}", store)
        val a = actor(user("u"))
        val ch = room.join(a)
        assertEquals("state", ch.next().event)
        assertEquals("users", ch.next().event) // the join handler, after the snapshot
        assertEquals("join", ch.next().event)
        val chat = room.act(a, Chat(Json.MutableObject("text" to "hi"))).await()
        assertEquals(Stage.APPLIED, chat.stage)
        ch.next().let {
            assertEquals("chat", it.event)
            val msg = Json.parse(it.data!!) as Json.Object
            assertEquals(1L, msg.getLong("seq"))
            assertEquals("u", msg.getString("from"))
            assertEquals("u", msg.getObject("author")!!.getString("id"))
            assertEquals("hi", msg.getString("text"))
            assertEquals("APPLIED", msg.getObject("_")!!.getString("stage"))
        }
        store.settled(1)
        assertEquals(listOf("hi"), store.tail(10).map { it.text })
        withTimeout(2000) { while (chat.stage != Stage.PERSISTED) kotlinx.coroutines.yield() }
    }

    @Test fun `the store seeds the sequence and the snapshot's tail`() = runBlocking {
        val store = MemoryChatHistory()
        val before = ChatRoom("chat-${n++}", store)
        repeat(60) { before.sendChat(Author("u", "u"), "m$it") }
        store.settled(60)
        before.stop()

        val after = ChatRoom("chat-${n++}", store)
        val ch = after.join(actor(user("v")))
        val snapshot = (Json.parse(ch.next().data!!) as Json.Object).getArray("history")!!
        assertEquals(50, snapshot.size) // the RAM tail, not the store's thousand
        assertEquals("m10", (snapshot.first() as Json.Object).getString("text"))
        assertEquals(61L, after.sendChat(Author("v", "v"), "next").seq)
        assertEquals("next", (Json.parse(ch.until("chat").data!!) as Json.Object).getString("text"))
    }

    @Test fun `scroll-back pages older than a seq, oldest first`() = runBlocking {
        val store = MemoryChatHistory(capacity = 5)
        val room = ChatRoom("chat-${n++}", store)
        repeat(8) { room.sendChat(Author("u", "u"), "m$it") }
        store.settled(8)
        assertEquals(listOf("m4", "m5"), room.before(7, 2).map { it.text }) // m0 is seq 1
        assertEquals(listOf("m3", "m4", "m5", "m6", "m7"), room.before(Long.MAX_VALUE, 10).map { it.text })
        assertEquals(emptyList(), room.before(1, 10))
    }
}
