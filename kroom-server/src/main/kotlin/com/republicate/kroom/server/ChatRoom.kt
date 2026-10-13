package com.republicate.kroom.server

import com.republicate.kson.Json
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Simple chat room state: the RAM tail, what a snapshot carries.
 */
data class ChatState(
    val history: ArrayDeque<ChatMessage> = ArrayDeque(),
    val maxHistory: Int = 50
)

/**
 * A chat room: a `chat` intent is numbered, kept in the RAM tail, broadcast, then persisted in
 * the background by [history] ([MemoryChatHistory] by default). A reconnect gets the tail in its
 * `state` and the replay of what it missed; older messages come from [before].
 */
open class ChatRoom(id: String, private val history: ChatHistory = MemoryChatHistory()) : Room<ChatState>(id) {
    override var state = ChatState()
    private val messageSeq = AtomicLong(0)

    init {
        historicizableEvents.add("chat")
        // seed the sequence and the tail from what the store kept: once, before the first join
        runBlocking { history.tail(state.maxHistory) }.forEach { state.history.addLast(it) }
        state.history.lastOrNull()?.let { messageSeq.set(it.seq) }
    }

    override fun needsHistory() = true

    override fun handleAction(actor: Actor, action: Json.Object): ActionResult {
        return when (action.getString("type")) {
            "chat" -> {
                val text = action.getString("text") ?: return ActionResult.Error("Missing text")
                sendChat(Author.of(actor), text)
                ActionResult.Success()
            }
            else -> ActionResult.Error("Unknown action type: ${action.getString("type")}")
        }
    }

    override fun stateToJson(): Json.Object = Json.Object(
        "id" to id,
        "history" to Json.Array(synchronized(state.history) { state.history.map { it.toJson() } }),
        "users" to Json.Array(users.values.map { Json.Object("userId" to it.userId, "name" to it.displayName) }),
        // Legacy field for backward compatibility
        "actors" to Json.Array(actors.values.map { Json.Object("id" to it.connectionId, "name" to it.name) })
    )

    /**
     * Say something in the room: numbered, broadcast, persisted behind.
     */
    fun sendChat(author: Author, text: String): ChatMessage {
        val msg = ChatMessage(
            seq = messageSeq.incrementAndGet(),
            author = author,
            text = text,
            timestamp = Instant.now().toString()
        )
        synchronized(state.history) {
            state.history.addLast(msg)
            while (state.history.size > state.maxHistory) state.history.removeFirst()
        }
        broadcast("chat", msg.toJson())
        scope.launch { history.append(msg) }
        return msg
    }

    /** Scroll-back: up to [n] messages older than [seq], oldest first. */
    suspend fun before(seq: Long, n: Int = 50): List<ChatMessage> = history.before(seq, n)

    override suspend fun onActorJoined(actor: Actor) {
        // Broadcast updated user list
        broadcast("users", Json.Object(
            "users" to Json.Array(users.values.map { Json.Object("userId" to it.userId, "name" to it.displayName) }),
            // Legacy field
            "actors" to Json.Array(actors.values.map { Json.Object("id" to it.connectionId, "name" to it.name) })
        ))
    }

    override suspend fun onActorLeft(actor: Actor, userFullyLeft: Boolean) {
        // Only broadcast if user fully left (not just closing one of multiple tabs)
        if (userFullyLeft) {
            broadcast("users", Json.Object(
                "users" to Json.Array(users.values.map { Json.Object("userId" to it.userId, "name" to it.displayName) }),
                // Legacy field
                "actors" to Json.Array(actors.values.map { Json.Object("id" to it.connectionId, "name" to it.name) })
            ))
        }
    }
}
