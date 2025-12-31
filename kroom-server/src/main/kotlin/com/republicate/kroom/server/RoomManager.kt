package com.republicate.kroom.server

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages chat room lifecycle - for backward compatibility
 * For generic rooms, use Lobby directly
 */
object RoomManager {
    private val logger = LoggerFactory.getLogger("kroom.manager")
    private val rooms = ConcurrentHashMap<String, ChatRoom>()

    /**
     * Get or create a chat room
     */
    fun getOrCreateRoom(name: String): ChatRoom {
        return rooms.computeIfAbsent(name) {
            logger.info("Creating room '$name'")
            ChatRoom(name)
        }
    }

    /**
     * Get a room if it exists
     */
    fun getRoom(name: String): ChatRoom? = rooms[name]

    /**
     * Remove a room
     */
    fun removeRoom(name: String) {
        rooms.remove(name)?.let { room ->
            logger.info("Removing room '$name'")
            room.stop()
        }
    }

    /**
     * List all room names
     */
    fun listRooms(): List<String> = rooms.keys.toList()

    /**
     * Get room info
     */
    fun getRoomInfo(name: String): RoomInfo? {
        return rooms[name]?.let { room ->
            RoomInfo(name, room.actorCount.value, room.getActors().map { it.name })
        }
    }

    /**
     * Get info for all rooms
     */
    fun getAllRoomInfo(): List<RoomInfo> {
        return rooms.map { (name, room) ->
            RoomInfo(name, room.actorCount.value, room.getActors().map { it.name })
        }
    }

    /**
     * Shutdown all rooms
     */
    fun shutdown() {
        logger.info("Shutting down all rooms")
        rooms.values.forEach { it.stop() }
        rooms.clear()
    }

    data class RoomInfo(
        val name: String,
        val userCount: Int,
        val users: List<String>
    )
}

/**
 * @deprecated Use Actor instead
 */
@Deprecated("Use Actor instead", ReplaceWith("Actor(connectionId, userId, name)"))
data class User(val login: String) {
    fun toActor(): Actor = Actor(connectionId = login, userId = login, name = login)
}
