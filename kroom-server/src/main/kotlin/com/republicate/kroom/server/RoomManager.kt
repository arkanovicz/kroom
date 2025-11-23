package com.republicate.kroom.server

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages room lifecycle and provides access to rooms
 */
object RoomManager {
    private val logger = LoggerFactory.getLogger("kroom.manager")
    private val rooms = ConcurrentHashMap<String, Room>()

    /**
     * Global lobby room - always exists
     */
    val lobby: Room by lazy {
        getOrCreateRoom("global")
    }

    /**
     * Get or create a room
     */
    fun getOrCreateRoom(name: String): Room {
        return rooms.computeIfAbsent(name) {
            logger.info("Creating room '$name'")
            Room(name)
        }
    }

    /**
     * Get a room if it exists
     */
    fun getRoom(name: String): Room? = rooms[name]

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
            RoomInfo(name, room.userCount.value, room.getUsers())
        }
    }

    /**
     * Get info for all rooms
     */
    fun getAllRoomInfo(): List<RoomInfo> {
        return rooms.map { (name, room) ->
            RoomInfo(name, room.userCount.value, room.getUsers())
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
