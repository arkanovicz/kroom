package com.republicate.kroom.server

import com.republicate.kson.Json
import io.ktor.sse.*

/**
 * A Table is a Room with fixed seats for players.
 *
 * Use this for turn-based games where players occupy specific positions (seats).
 * When an actor joins, they can either:
 * - Claim an empty seat (become a player)
 * - Join as a spectator if all seats are taken or if they choose to spectate
 *
 * The Table tracks the mapping between actors and seats, enabling:
 * - Player identification on reconnect (by name matching)
 * - Targeted messages to specific seats
 * - Seat-aware state serialization
 *
 * @param S The type of game state
 * @param id Unique table identifier
 * @param seatCount Number of seats at this table
 */
abstract class Table<S : Any>(id: String, val seatCount: Int) : Room<S>(id) {

    /**
     * Seat information
     */
    data class Seat(
        val number: Int,        // 1-indexed seat number
        var playerName: String? = null,  // Name of player in this seat (null if empty)
        var actorId: String? = null      // Current actor ID (null if disconnected)
    ) {
        val isEmpty: Boolean get() = playerName == null
        val isConnected: Boolean get() = actorId != null
    }

    // Seats array (1-indexed, index 0 unused for cleaner API)
    private val seats: Array<Seat> = Array(seatCount + 1) { Seat(it) }

    /**
     * Get seat by number (1-indexed)
     */
    fun getSeat(number: Int): Seat? = seats.getOrNull(number)

    /**
     * Get all occupied seats
     */
    fun getOccupiedSeats(): List<Seat> = seats.drop(1).filter { !it.isEmpty }

    /**
     * Get the seat for an actor (by actor ID)
     */
    fun getSeatForActor(actorId: String): Seat? = seats.drop(1).find { it.actorId == actorId }

    /**
     * Get the seat for a player (by name)
     */
    fun getSeatForPlayer(name: String): Seat? = seats.drop(1).find { it.playerName == name }

    /**
     * Find an empty seat
     */
    fun findEmptySeat(): Seat? = seats.drop(1).find { it.isEmpty }

    /**
     * Check if table is full (all seats occupied)
     */
    fun isFull(): Boolean = seats.drop(1).all { !it.isEmpty }

    /**
     * Assign a player to a seat.
     * @param actorId The actor's ID
     * @param playerName The player's name
     * @param requestedSeat Optional specific seat number to claim (1-indexed)
     * @return The seat number, or null if no seat available or requested seat is taken
     */
    protected fun assignSeat(actorId: String, playerName: String, requestedSeat: Int? = null): Int? {
        // Check if player already has a seat (reconnect case)
        val existingSeat = getSeatForPlayer(playerName)
        if (existingSeat != null) {
            existingSeat.actorId = actorId
            return existingSeat.number
        }

        // If specific seat requested
        if (requestedSeat != null) {
            val seat = getSeat(requestedSeat)
            if (seat == null || !seat.isEmpty) return null
            seat.playerName = playerName
            seat.actorId = actorId
            return seat.number
        }

        // Find first empty seat
        val seat = findEmptySeat() ?: return null
        seat.playerName = playerName
        seat.actorId = actorId
        return seat.number
    }

    /**
     * Clear actor from their seat (disconnect, but keep player name for reconnect)
     */
    protected fun disconnectSeat(actorId: String) {
        getSeatForActor(actorId)?.let { it.actorId = null }
    }

    /**
     * Remove player from seat entirely (leave game)
     */
    protected fun vacateSeat(seatNumber: Int) {
        seats.getOrNull(seatNumber)?.let {
            it.playerName = null
            it.actorId = null
        }
    }

    /**
     * Called when actor joins - override to customize seat assignment.
     * Default behavior: assign to first empty seat or reconnect to existing seat.
     * Returns the assigned seat number, or null if joining as spectator.
     */
    protected open suspend fun assignActorToSeat(actor: Actor): Int? {
        return assignSeat(actor.id, actor.name)
    }

    /**
     * Send state to actor with their seat number included.
     * Override stateToJsonForSeat() instead of stateToJson() for seat-aware serialization.
     */
    override suspend fun sendStateTo(actor: Actor) {
        val seatNumber = getSeatForActor(actor.id)?.number
        actor.channel?.send(ServerSentEvent(
            data = stateToJsonForSeat(seatNumber).toString(),
            event = "state"
        ))
    }

    /**
     * Convert state to JSON, including the viewer's seat number.
     * Override this for seat-aware state serialization.
     */
    protected open fun stateToJsonForSeat(seatNumber: Int?): Json.Object {
        val base = stateToJson()
        val json = Json.MutableObject()
        for (key in base.keys) {
            json[key] = base[key]
        }
        json["mySeat"] = seatNumber
        return json
    }

    /**
     * Send event to a specific seat
     */
    fun sendToSeat(seatNumber: Int, event: String, data: Json.Object) {
        seats.getOrNull(seatNumber)?.actorId?.let { actorId ->
            getActor(actorId)?.let { actor ->
                sendTo(actor, event, data)
            }
        }
    }

    /**
     * Broadcast to all seated players (not spectators)
     */
    fun broadcastToSeated(event: String, data: Json.Object) {
        seats.drop(1).forEach { seat ->
            seat.actorId?.let { actorId ->
                getActor(actorId)?.let { actor ->
                    sendTo(actor, event, data)
                }
            }
        }
    }

    override suspend fun onActorJoined(actor: Actor) {
        val seatNumber = assignActorToSeat(actor)
        if (seatNumber != null) {
            onPlayerSeated(actor, seatNumber)
        } else {
            onSpectatorJoined(actor as? Spectator ?: Spectator(actor.id, actor.name))
        }
    }

    override suspend fun onActorLeft(actor: Actor) {
        val seat = getSeatForActor(actor.id)
        if (seat != null) {
            disconnectSeat(actor.id)
            onPlayerDisconnected(actor, seat.number)
        }
    }

    /**
     * Called when a player takes a seat
     */
    protected open suspend fun onPlayerSeated(actor: Actor, seatNumber: Int) {}

    /**
     * Called when a seated player disconnects (seat retained for reconnect)
     */
    protected open suspend fun onPlayerDisconnected(actor: Actor, seatNumber: Int) {}

    override fun toJson(): Json.Object = Json.Object(
        "id" to id,
        "seatCount" to seatCount,
        "seats" to Json.Array(seats.drop(1).map { seat ->
            Json.Object(
                "number" to seat.number,
                "player" to seat.playerName,
                "connected" to seat.isConnected
            )
        }),
        "actorCount" to actorCount.value,
        "spectatorCount" to spectatorCount.value
    )
}
