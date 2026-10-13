package com.republicate.kroom.server

import com.republicate.kson.Json
import io.ktor.sse.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** Pins the chat history contract: the room numbers, the store keeps, the loop never waits. */
class ChatRoomTest {

    private var n = 0
    private fun user(id: String) = Users.getOrCreate(id, id)
    private fun actor(user: User) = Actor(Actor.connectionId(user, "t1"), user)
    private suspend fun Channel<ServerSentEvent>.next(): ServerSentEvent = withTimeout(2000) { receive() }
    private suspend fun ChatHistory.settled(seq: Long) = withTimeout(2000) { while (tail(1).lastOrNull()?.seq != seq) kotlinx.coroutines.yield() }

    @BeforeTest fun clean() = Users.clear()

    @Test fun `a message is numbered, broadcast, then kept`() = runBlocking {
        val store = MemoryChatHistory()
        val room = ChatRoom("chat-${n++}", store)
        val a = actor(user("u"))
        val ch = room.join(a)
        assertEquals("state", ch.next().event)
        assertEquals("users", ch.next().event) // the join hook, after the snapshot
        room.act(a, Json.Object("type" to "chat", "text" to "hi")).await()
        ch.next().let {
            assertEquals("chat", it.event)
            val msg = Json.parse(it.data!!)!!.asObject()
            assertEquals(1L, msg.getLong("seq"))
            assertEquals("u", msg.getString("from"))
            assertEquals("u", msg.getObject("author")!!.getString("id"))
            assertEquals("hi", msg.getString("text"))
        }
        store.settled(1)
        assertEquals(listOf("hi"), store.tail(10).map { it.text })
    }

    @Test fun `the store seeds the sequence and the snapshot's tail`() = runBlocking {
        val store = MemoryChatHistory()
        val before = ChatRoom("chat-${n++}", store)
        repeat(60) { before.sendChat(Author("u", "u"), "m$it") }
        store.settled(60)
        before.stop()

        val after = ChatRoom("chat-${n++}", store)
        val ch = after.join(actor(user("v")))
        val snapshot = Json.parse(ch.next().data!!)!!.asObject().getArray("history")!!
        assertEquals(50, snapshot.size) // the RAM tail, not the store's thousand
        assertEquals("m10", (snapshot.first() as Json.Object).getString("text"))
        assertEquals(61L, after.sendChat(Author("v", "v"), "next").seq)
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
