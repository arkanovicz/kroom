package com.republicate.kroom.server

import com.republicate.kson.Json
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Simple chat room state
 */
data class ChatState(
    val history: MutableList<ChatMessage> = mutableListOf(),
    val maxHistory: Int = 50
)

/**
 * Chat message
 */
data class ChatMessage(
    val seq: Long,
    val from: String?,
    val text: String,
    val timestamp: String
) {
    fun toJson(): Json.Object = Json.Object(
        "seq" to seq,
        "from" to from,
        "text" to text,
        "timestamp" to timestamp
    )
}

/**
 * A simple chat room implementation for demonstration and testing
 */
class ChatRoom(id: String) : Room<ChatState>(id) {
    override var state = ChatState()
    private val messageSeq = AtomicLong(1)

    override fun handleAction(actor: Actor, action: Json.Object): ActionResult {
        return when (action.getString("type")) {
            "chat" -> {
                val text = action.getString("text") ?: return ActionResult.Error("Missing text")
                sendChat(actor.name, text)
                ActionResult.Success()
            }
            else -> ActionResult.Error("Unknown action type: ${action.getString("type")}")
        }
    }

    override fun stateToJson(): Json.Object = Json.Object(
        "id" to id,
        "history" to Json.Array(state.history.map { it.toJson() }),
        "actors" to Json.Array(actors.values.map { Json.Object("id" to it.id, "name" to it.name) })
    )

    /**
     * Send a chat message to the room
     */
    fun sendChat(from: String?, text: String) {
        val msg = ChatMessage(
            seq = messageSeq.incrementAndGet(),
            from = from,
            text = text,
            timestamp = Instant.now().toString()
        )

        synchronized(state.history) {
            state.history.add(msg)
            if (state.history.size > state.maxHistory) {
                state.history.removeAt(0)
            }
        }

        broadcast("chat", msg.toJson())
    }

    override suspend fun onActorJoined(actor: Actor) {
        // Broadcast updated actor list
        broadcast("actors", Json.Object(
            "actors" to Json.Array(actors.values.map { Json.Object("id" to it.id, "name" to it.name) })
        ))
    }

    override suspend fun onActorLeft(actor: Actor) {
        // Broadcast updated actor list
        broadcast("actors", Json.Object(
            "actors" to Json.Array(actors.values.map { Json.Object("id" to it.id, "name" to it.name) })
        ))
    }
}
