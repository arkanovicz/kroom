package com.republicate.kroom.server

import io.ktor.sse.*
import kotlinx.coroutines.channels.Channel

/**
 * Base class for room participants
 * An Actor represents any entity that can interact with a room
 *
 * @param connectionId Unique ID for this connection (changes on reconnect)
 * @param userId Persistent identity (null for anonymous actors)
 * @param name Display name
 */
open class Actor(
    val connectionId: String,
    val userId: String? = null,
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
     * Whether this actor has a persistent identity
     */
    val isAuthenticated: Boolean
        get() = userId != null

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
        return connectionId == other.connectionId
    }

    override fun hashCode(): Int = connectionId.hashCode()

    override fun toString(): String = "Actor($connectionId, userId=$userId, $name)"

    // Legacy compatibility: 'id' as alias for connectionId
    @Deprecated("Use connectionId instead", ReplaceWith("connectionId"))
    val id: String get() = connectionId
}

/**
 * A Spectator is an Actor that can observe but not interact
 * Spectators receive broadcasts but cannot perform actions
 */
class Spectator(
    connectionId: String,
    userId: String? = null,
    name: String
) : Actor(connectionId, userId, name) {
    override fun toString(): String = "Spectator($connectionId, userId=$userId, $name)"
}
