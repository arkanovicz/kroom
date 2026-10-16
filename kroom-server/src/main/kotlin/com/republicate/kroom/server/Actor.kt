package com.republicate.kroom.server

import com.republicate.kroom.event.Presence
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
    override val name: String = user?.displayName ?: "Anonymous"
) : com.republicate.kroom.event.Actor {
    /** The user's id; a connection without a user is its own. */
    override val id: String get() = user?.userId ?: connectionId
    override val client: String? get() = tab.takeIf { it.isNotEmpty() }

    /** What the client last reported of itself; the user's is composed from its clients'. */
    var status: Presence = Presence.ONLINE
    /** A tab, an app: what the client said it is at connect. */
    var kind: String = "web"

    /**
     * SSE channel for this actor's connection.
     * Null when disconnected.
     */
    var channel: Channel<ServerSentEvent>? = null
        internal set

    /** The tab part of the connection id (see [connectionId]): metadata the browser filters on. */
    val tab: String get() = connectionId.substringAfter('/', "")

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

    companion object {
        /**
         * A connection id is the user's id and the tab's, composed here and nowhere else: a tab id
         * is a routing key the browser chose, never a credential — composed under the authenticated
         * user it cannot reach another user's slot. No tab id: a fresh id per connection.
         */
        fun connectionId(user: User, tabId: String?): String =
            "${user.userId}/${tabId?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID()}"

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
