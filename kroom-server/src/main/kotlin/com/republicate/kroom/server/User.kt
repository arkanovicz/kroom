package com.republicate.kroom.server

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents a persistent user identity that can have multiple connections.
 *
 * A User is the top-level identity that persists across connections and rooms.
 * Each user can have multiple simultaneous connections (e.g., multiple browser tabs)
 * to different rooms or even to the same room.
 *
 * @param userId The unique persistent identifier for this user
 * @param displayName The user's display name
 */
class User(
    val userId: String,
    val displayName: String
) {
    /**
     * Connections per room (roomId -> set of Actors).
     * Internal because Room manages this via addConnection/removeConnection.
     */
    internal val connectionsByRoom = ConcurrentHashMap<String, MutableSet<Actor>>()

    /**
     * Get all connections this user has in a specific room.
     * Returns empty set if user is not connected to that room.
     */
    fun getConnectionsInRoom(roomId: String): Set<Actor> =
        connectionsByRoom[roomId]?.toSet() ?: emptySet()

    /**
     * Check if user is connected to a specific room (has at least one connection).
     */
    fun isConnectedTo(roomId: String): Boolean =
        connectionsByRoom[roomId]?.isNotEmpty() == true

    /**
     * Get all room IDs this user is currently connected to.
     */
    fun connectedRoomIds(): Set<String> =
        connectionsByRoom.filterValues { it.isNotEmpty() }.keys.toSet()

    /**
     * Total number of active connections across all rooms.
     */
    val totalConnectionCount: Int
        get() = connectionsByRoom.values.sumOf { it.size }

    /**
     * Add a connection to a room.
     * Called by Room when actor joins.
     */
    internal fun addConnection(roomId: String, actor: Actor) {
        connectionsByRoom.getOrPut(roomId) { ConcurrentHashMap.newKeySet() }.add(actor)
    }

    /**
     * Remove a connection from a room.
     * Called by Room when actor leaves.
     * @return true if user has no more connections to this room
     */
    internal fun removeConnection(roomId: String, actor: Actor): Boolean {
        val connections = connectionsByRoom[roomId] ?: return true
        connections.remove(actor)
        if (connections.isEmpty()) {
            connectionsByRoom.remove(roomId)
            return true
        }
        return false
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is User) return false
        return userId == other.userId
    }

    override fun hashCode(): Int = userId.hashCode()

    override fun toString(): String = "User($userId, $displayName)"
}

/**
 * Global registry for User instances.
 *
 * Maintains a single User instance per userId across the application.
 * This ensures that when the same user connects from multiple tabs/devices,
 * they share the same User object and their connections are properly tracked.
 */
object Users {
    private val logger = LoggerFactory.getLogger("kroom.users")
    private val users = ConcurrentHashMap<String, User>()

    /**
     * Get or create a User for the given userId.
     * If the user already exists, returns the existing instance (displayName is not updated).
     * If the user doesn't exist, creates a new User with the given displayName.
     */
    fun getOrCreate(userId: String, displayName: String): User {
        return users.computeIfAbsent(userId) {
            logger.debug("Creating new user: $userId ($displayName)")
            User(userId, displayName)
        }
    }

    /**
     * Get a User by userId, or null if not found.
     */
    fun get(userId: String): User? = users[userId]

    /**
     * Check if a user exists in the registry.
     */
    fun exists(userId: String): Boolean = users.containsKey(userId)

    /**
     * Remove a user from the registry.
     * Should only be called when user has no active connections.
     */
    internal fun remove(userId: String): User? {
        return users.remove(userId)?.also {
            logger.debug("Removed user: $userId")
        }
    }

    /**
     * Get all currently tracked users.
     */
    fun all(): Collection<User> = users.values.toList()

    /**
     * Number of users currently tracked.
     */
    fun count(): Int = users.size

    /**
     * Clear all users (for testing or shutdown).
     */
    fun clear() {
        users.clear()
        logger.info("Users registry cleared")
    }
}
