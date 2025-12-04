package com.republicate.kroom.server

import io.ktor.sse.*
import kotlinx.coroutines.channels.Channel

/**
 * Base class for room participants
 * An Actor represents any entity that can interact with a room
 */
open class Actor(
    val id: String,
    val name: String
) {
    /**
     * SSE channel for this actor's connection
     * Null when disconnected
     */
    var channel: Channel<ServerSentEvent>? = null
        internal set

    /**
     * Whether the actor is currently connected
     */
    val isConnected: Boolean
        get() = channel != null

    /**
     * Disconnect this actor
     */
    fun disconnect() {
        channel?.close()
        channel = null
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Actor) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Actor($id, $name)"
}

/**
 * A Spectator is an Actor that can observe but not interact
 * Spectators receive broadcasts but cannot perform actions
 */
class Spectator(
    id: String,
    name: String
) : Actor(id, name) {
    override fun toString(): String = "Spectator($id, $name)"
}
