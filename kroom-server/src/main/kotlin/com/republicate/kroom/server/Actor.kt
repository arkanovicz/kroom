package com.republicate.kroom.server

import io.ktor.sse.*
import kotlinx.coroutines.channels.Channel

/**
 * Base class for room participants.
 * An Actor represents a single connection to a room.
 *
 * A User can have multiple Actors (connections) to the same or different rooms.
 * For example, if a user opens two browser tabs to the same game, each tab
 * creates its own Actor, but both share the same User.
 *
 * @param connectionId Unique ID for this connection (changes on reconnect)
 * @param user The User this actor belongs to (null for anonymous connections)
 * @param name Display name for this actor (defaults to user's displayName if available)
 */
open class Actor(
    val connectionId: String,
    val user: User? = null,
    val name: String = user?.displayName ?: "Anonymous"
) {
    /**
     * SSE channel for this actor's connection.
     * Null when disconnected.
     */
    var channel: Channel<ServerSentEvent>? = null
        internal set

    /**
     * Whether the actor is currently connected.
     */
    val isConnected: Boolean
        get() = channel != null

    /**
     * Whether this actor has a persistent identity (belongs to a User).
     */
    val isAuthenticated: Boolean
        get() = user != null

    /**
     * Disconnect this actor.
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

    override fun toString(): String = "Actor($connectionId, user=${user?.userId}, $name)"

    // ========== Backward compatibility ==========

    /**
     * Legacy compatibility: userId as property.
     * @deprecated Use user?.userId instead
     */
    @Deprecated("Use user?.userId instead", ReplaceWith("user?.userId"))
    val userId: String?
        get() = user?.userId

    /**
     * Legacy compatibility: 'id' as alias for connectionId.
     * @deprecated Use connectionId instead
     */
    @Deprecated("Use connectionId instead", ReplaceWith("connectionId"))
    val id: String get() = connectionId

    companion object {
        /**
         * Create an Actor with a userId string (backward compatible factory).
         * If userId is non-null, looks up or creates a User.
         * @deprecated Use Actor(connectionId, user, name) constructor instead
         */
        @Deprecated("Use Actor(connectionId, user, name) constructor instead")
        @JvmStatic
        fun create(connectionId: String, userId: String?, name: String): Actor {
            val user = userId?.let { Users.getOrCreate(it, name) }
            return Actor(connectionId, user, name)
        }
    }
}

/**
 * A Spectator is an Actor that can observe but not interact.
 * Spectators receive broadcasts but cannot perform actions.
 */
class Spectator(
    connectionId: String,
    user: User? = null,
    name: String = user?.displayName ?: "Anonymous"
) : Actor(connectionId, user, name) {
    override fun toString(): String = "Spectator($connectionId, user=${user?.userId}, $name)"

    companion object {
        /**
         * Create a Spectator with a userId string (backward compatible factory).
         * @deprecated Use Spectator(connectionId, user, name) constructor instead
         */
        @Deprecated("Use Spectator(connectionId, user, name) constructor instead")
        @JvmStatic
        fun create(connectionId: String, userId: String?, name: String): Spectator {
            val user = userId?.let { Users.getOrCreate(it, name) }
            return Spectator(connectionId, user, name)
        }
    }
}
