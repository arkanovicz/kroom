package com.republicate.kroom.server

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** The process's rooms by id: registered by the host, or built on first use and forgotten once closed. */
object Rooms {
    private val logger = LoggerFactory.getLogger("kroom.rooms")
    private val rooms = ConcurrentHashMap<String, Room<*>>()

    /**
     * Register a room
     */
    fun <S : Any> register(room: Room<S>): Room<S> {
        room.lobby?.adopt(room)
        rooms[room.id] = room
        logger.info("Room '${room.id}' registered")
        return room
    }

    /**
     * Get a room by ID
     */
    @Suppress("UNCHECKED_CAST")
    fun <S : Any> get(id: String): Room<S>? = rooms[id] as? Room<S>

    private val building = Mutex()

    /**
     * The running room [id], built by [build] on first use (null: no such room). One build at a time: the room is
     * looked up, then under the lock looked up again before building, so two first calls build it once. A room
     * built here closes once idle and is forgotten; the next call builds it anew. A [build] may call this for
     * another room (its lobby): it holds the lock already.
     */
    suspend fun getOrCreate(id: String, build: suspend (id: String) -> Room<*>?): Room<*>? =
        live(id) ?: if (currentCoroutineContext()[Building.Key] != null) create(id, build)
        else building.withLock { withContext(Building) { create(id, build) } }

    private suspend fun create(id: String, build: suspend (id: String) -> Room<*>?): Room<*>? {
        live(id)?.let { return it }
        // a lobby may close between the build that fetched it and here: the room is built once more, with the new one
        var room = build(id) ?: return null
        if (!adopted(room)) room = build(id)?.also { check(adopted(it)) { "room '$id': its lobby is closed" } } ?: return null
        require(room.id == id) { "built '${room.id}' for '$id'" }
        room.onDemand = true
        rooms[id] = room
        room.idle()
        logger.info("Room '$id' built on demand")
        return room
    }

    /** The room is in its lobby's group from here on; false: that lobby closed, and the room is stopped. */
    private fun adopted(room: Room<*>): Boolean = try {
        room.lobby?.adopt(room)
        true
    } catch (e: IllegalStateException) {
        room.stop()
        false
    }

    /** Marks the coroutine that holds the lock, through the builds it runs. */
    private object Building : AbstractCoroutineContextElement(Key) { object Key : CoroutineContext.Key<Building> }

    private fun live(id: String): Room<*>? = rooms[id]?.takeIf { it.running }

    /** A room that closed itself: forgotten, unless another already took its id. */
    internal fun forget(room: Room<*>) {
        if (rooms.remove(room.id, room)) logger.info("Room '${room.id}' forgotten")
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
