package com.republicate.kroom.server

import com.republicate.kroom.event.Audience
import com.republicate.kroom.event.Event
import com.republicate.kroom.event.Stage
import com.republicate.kson.Json
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/** Simple chat room state: the RAM tail, what a snapshot carries. */
data class ChatState(
    val history: ArrayDeque<ChatMessage> = ArrayDeque(),
    val maxHistory: Int = 50
)

/** A line of chat: the text comes in, the room adds who, when and the message's seq. */
class Chat(json: Json.MutableObject = Json.MutableObject()) : Event("chat", Audience.ROOM, json = json) {
    val text by string().required
    var seq by long()
    var from by string()
    var author by obj()
    var timestamp by string()

    fun toMessage() = ChatMessage(seq!!, Author(author!!.getString("id")!!, author!!.getString("name")!!, author!!.getString("avatar")), text, timestamp!!)
}

/**
 * A chat room: a `chat` event is numbered, kept in the RAM tail, emitted, then persisted in the
 * background by [history] ([MemoryChatHistory] by default). A join gets the tail in its `state`;
 * older messages come from [before].
 */
open class ChatRoom(id: String, private val history: ChatHistory = MemoryChatHistory()) : Room<ChatState>(id) {
    override var state = ChatState()
    private val messageSeq = AtomicLong(0)

    // one writer, in seq order: the store sees the messages as the room numbered them
    private val writer = Channel<Chat>(Channel.UNLIMITED)

    init {
        // seed the sequence and the tail from what the store kept: once, before the first join
        runBlocking { history.tail(state.maxHistory) }.forEach { state.history.addLast(it) }
        state.history.lastOrNull()?.let { messageSeq.set(it.seq) }
        scope.launch { for (chat in writer) { history.append(chat.toMessage()); trace(chat.persist()) } }

        on(::Chat) { actor, chat -> record(chat, Author.of(actor)); null }
        on(::Join) { _, _ -> broadcastUsers(); null }
        on(::Leave) { _, leave -> if (leave.last == true) broadcastUsers(); null }
    }

    override fun stateToJson(): Json.Object = Json.Object(
        "id" to id,
        "history" to Json.Array(synchronized(state.history) { state.history.map { it.toJson() } }),
        "users" to usersJson()
    )

    /** Say something in the room from elsewhere than a door (a bridge, a route): same life from APPLIED on. */
    fun sendChat(author: Author, text: String): ChatMessage {
        val chat = Chat(Json.MutableObject("text" to text))
        record(chat, author)
        emit(chat)
        return chat.toMessage()
    }

    private fun record(chat: Chat, author: Author) {
        chat.seq = messageSeq.incrementAndGet()
        chat.from = author.name
        chat.author = author.toJson()
        chat.timestamp = Instant.now().toString()
        synchronized(state.history) {
            state.history.addLast(chat.toMessage())
            while (state.history.size > state.maxHistory) state.history.removeFirst()
        }
    }

    override fun emitted(event: Event) {
        if (event is Chat && event.stage == Stage.APPLIED) writer.trySend(event)
    }

    /** Scroll-back: up to [n] messages older than [seq], oldest first. */
    suspend fun before(seq: Long, n: Int = 50): List<ChatMessage> = history.before(seq, n)

    private fun usersJson() = Json.Array(users.values.map { Json.Object("userId" to it.userId, "name" to it.displayName) })
    private fun broadcastUsers() = broadcast("users", Json.Object("users" to usersJson()))
}
