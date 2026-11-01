package com.republicate.kroom.server

import com.republicate.kson.Json
import java.util.ArrayDeque

/**
 * Who said it: the user's id, or an external one (`discord:<snowflake>`); the avatar is what a
 * bridge shows on the other side.
 */
data class Author(val id: String, val name: String, val avatar: String? = null) {
    fun toJson(): Json.Object = Json.Object("id" to id, "name" to name, "avatar" to avatar)

    companion object {
        fun of(actor: Actor) = Author(actor.id, actor.name, actor.avatar)
    }
}

/**
 * A chat message; `seq` is the room's, monotonic, assigned before the store sees it.
 */
data class ChatMessage(
    val seq: Long,
    val author: Author,
    val text: String,
    val timestamp: String
) {
    fun toJson(): Json.Object = Json.Object(
        "seq" to seq,
        "from" to author.name,
        "author" to author.toJson(),
        "text" to text,
        "timestamp" to timestamp
    )
}

/**
 * Where a room's chat lives beyond its RAM tail — one instance per room. The loop never waits on
 * it: a message is numbered and broadcast first, then [append]ed in the background; [tail] runs
 * once, when the room starts, to seed the sequence and the snapshot; [before] serves scroll-back
 * from a route. Catch-up over a short gap is the stream's replay, not the store's.
 */
interface ChatHistory {
    /** The last [n] messages, oldest first. */
    suspend fun tail(n: Int): List<ChatMessage>

    /** Persist a message; called off the loop, in order of seq. */
    suspend fun append(message: ChatMessage)

    /** Up to [n] messages older than [seq], oldest first. */
    suspend fun before(seq: Long, n: Int): List<ChatMessage>
}

/**
 * The default: the last [capacity] messages in memory, gone with the process.
 */
class MemoryChatHistory(private val capacity: Int = 1000) : ChatHistory {
    private val messages = ArrayDeque<ChatMessage>()

    override suspend fun tail(n: Int): List<ChatMessage> = synchronized(messages) { messages.toList().takeLast(n) }

    override suspend fun append(message: ChatMessage) = synchronized(messages) {
        messages.addLast(message)
        while (messages.size > capacity) messages.removeFirst()
    }

    override suspend fun before(seq: Long, n: Int): List<ChatMessage> = synchronized(messages) {
        messages.filter { it.seq < seq }.takeLast(n)
    }
}
