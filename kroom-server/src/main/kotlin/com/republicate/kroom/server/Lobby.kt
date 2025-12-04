package com.republicate.kroom.server

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry for rooms
 * Provides room lifecycle management and discovery
 */
object Lobby {
    private val logger = LoggerFactory.getLogger("kroom.lobby")
    private val rooms = ConcurrentHashMap<String, Room<*>>()

    /**
     * Register a room
     */
    fun <S : Any> register(room: Room<S>): Room<S> {
        rooms[room.id] = room
        logger.info("Room '${room.id}' registered")
        return room
    }

    /**
     * Get a room by ID
     */
    @Suppress("UNCHECKED_CAST")
    fun <S : Any> get(id: String): Room<S>? = rooms[id] as? Room<S>

    /**
     * Get room or create with factory
     */
    @Suppress("UNCHECKED_CAST")
    fun <S : Any> getOrCreate(id: String, factory: () -> Room<S>): Room<S> {
        return rooms.computeIfAbsent(id) {
            logger.info("Creating room '$id'")
            factory()
        } as Room<S>
    }

    /**
     * Remove a room
     */
    fun remove(id: String): Room<*>? {
        return rooms.remove(id)?.also { room ->
            logger.info("Room '$id' removed")
            room.stop()
        }
    }

    /**
     * List all room IDs
     */
    fun listIds(): List<String> = rooms.keys().toList()

    /**
     * List all rooms
     */
    fun listRooms(): List<Room<*>> = rooms.values.toList()

    /**
     * Get room count
     */
    fun count(): Int = rooms.size

    /**
     * Check if room exists
     */
    fun exists(id: String): Boolean = rooms.containsKey(id)

    /**
     * Shutdown all rooms
     */
    fun shutdown() {
        logger.info("Shutting down all rooms")
        rooms.values.forEach { it.stop() }
        rooms.clear()
    }
}
