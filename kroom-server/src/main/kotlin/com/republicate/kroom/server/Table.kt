package com.republicate.kroom.server

import com.republicate.kson.Json
import io.ktor.sse.*

/**
 * Player connection status for seats.
 */
enum class PlayerStatus {
    ONLINE,   // Connected and active
    IDLE,     // Connected but inactive (no input for a while)
    AWAY,     // Tab/app not visible
    OFFLINE   // Disconnected
}

/**
 * A Table is a Room with fixed seats for players.
 *
 * Use this for turn-based games where players occupy specific positions (seats).
 * When an actor joins, they can either:
 * - Claim an empty seat (become a player)
 * - Join as a spectator if all seats are taken or if they choose to spectate
 *
 * The Table tracks the mapping between users and seats, enabling:
 * - Player identification on reconnect (by user identity)
 * - Targeted messages to specific seats (reaches all user's connections)
 * - Seat-aware state serialization
 *
 * @param S The type of game state
 * @param id Unique table identifier
 * @param seatCount Number of seats at this table
 */
abstract class Table<S : Any>(id: String, val seatCount: Int) : Room<S>(id) {

    /**
     * Seat information.
     * A seat is occupied by a User (not a single connection).
     * The user may have multiple connections (tabs) to the same seat.
     */
    data class Seat(
        val number: Int,              // 1-indexed seat number
        var user: User? = null,       // User occupying this seat (null if empty)
        var playerName: String? = null,  // Display name (may differ from user.displayName)
        var status: PlayerStatus = PlayerStatus.OFFLINE  // Player connection status
    ) {
        val isEmpty: Boolean get() = user == null

        /**
         * Check if this seat's user is connected to the room.
         * @param roomId The room ID to check connections for
         */
        fun isConnected(roomId: String): Boolean = user?.isConnectedTo(roomId) == true

        // Legacy compatibility
        @Deprecated("Use user?.userId instead")
        val userId: String? get() = user?.userId
    }

    // Seats array (1-indexed, index 0 unused for cleaner API)
    private val seats: Array<Seat> = Array(seatCount + 1) { Seat(it) }

    /**
     * Get seat by number (1-indexed).
     */
    fun getSeat(number: Int): Seat? = seats.getOrNull(number)

    /**
     * Get all occupied seats.
     */
    fun getOccupiedSeats(): List<Seat> = seats.drop(1).filter { !it.isEmpty }

    /**
     * Get the seat for a user.
     */
    fun getSeatForUser(user: User): Seat? = seats.drop(1).find { it.user == user }

    /**
     * Get the seat for a user by userId.
     */
    fun getSeatForUser(userId: String): Seat? = seats.drop(1).find { it.user?.userId == userId }

    /**
     * Get the seat for a player (by name) - for display/legacy purposes.
     */
    fun getSeatForPlayer(name: String): Seat? = seats.drop(1).find { it.playerName == name }

    /**
     * Find an empty seat.
     */
    fun findEmptySeat(): Seat? = seats.drop(1).find { it.isEmpty }

    /**
     * Check if table is full (all seats occupied).
     */
    fun isFull(): Boolean = seats.drop(1).all { !it.isEmpty }

    /**
     * Assign a user to a seat.
     * @param user The user to assign
     * @param playerName The player's display name
     * @param requestedSeat Optional specific seat number to claim (1-indexed)
     * @return The seat number, or null if no seat available or requested seat is taken
     */
    protected fun assignSeat(user: User, playerName: String, requestedSeat: Int? = null): Int? {
        // Check if user already has a seat (reconnect case)
        val existingSeat = getSeatForUser(user)
        if (existingSeat != null) {
            existingSeat.playerName = playerName  // Update name in case it changed
            existingSeat.status = PlayerStatus.ONLINE
            return existingSeat.number
        }

        // If specific seat requested
        if (requestedSeat != null) {
            val seat = getSeat(requestedSeat)
            if (seat == null || !seat.isEmpty) return null
            seat.user = user
            seat.playerName = playerName
            seat.status = PlayerStatus.ONLINE
            return seat.number
        }

        // Find first empty seat
        val seat = findEmptySeat() ?: return null
        seat.user = user
        seat.playerName = playerName
        seat.status = PlayerStatus.ONLINE
        return seat.number
    }

    /**
     * Legacy: Assign a seat using connectionId and userId strings.
     * @deprecated Use assignSeat(user, playerName, requestedSeat) instead
     */
    @Deprecated("Use assignSeat(user, playerName, requestedSeat) instead")
    protected fun assignSeat(connectionId: String, userId: String, playerName: String, requestedSeat: Int? = null): Int? {
        val user = Users.getOrCreate(userId, playerName)
        return assignSeat(user, playerName, requestedSeat)
    }

    /**
     * Mark seat as disconnected (user has no more connections to this room).
     * Called when user's last connection leaves.
     */
    protected fun disconnectSeat(user: User) {
        getSeatForUser(user)?.let {
            it.status = PlayerStatus.OFFLINE
        }
    }

    /**
     * Remove player from seat entirely (leave game).
     */
    protected fun vacateSeat(seatNumber: Int) {
        seats.getOrNull(seatNumber)?.let {
            it.user = null
            it.playerName = null
            it.status = PlayerStatus.OFFLINE
        }
    }

    /**
     * Called when actor joins - override to customize seat assignment.
     * Default behavior: assign to first empty seat or reconnect to existing seat.
     * Returns the assigned seat number, or null if joining as spectator.
     */
    protected open suspend fun assignActorToSeat(actor: Actor): Int? {
        val user = actor.user ?: return null  // No identity = spectator
        return assignSeat(user, actor.name)
    }

    /**
     * Send state to actor with their seat number included.
     * Override stateToJsonForSeat() instead of stateToJson() for seat-aware serialization.
     */
    override suspend fun sendStateTo(actor: Actor) {
        val seatNumber = actor.user?.let { getSeatForUser(it)?.number }
        actor.channel?.send(ServerSentEvent(
            data = stateToJsonForSeat(seatNumber).toString(),
            event = "state"
        ))
    }

    /**
     * Convert state to JSON, including the viewer's seat number and spectators.
     * Override this for seat-aware state serialization.
     */
    protected open fun stateToJsonForSeat(seatNumber: Int?): Json.Object {
        val base = stateToJson()
        val json = Json.MutableObject()
        for (key in base.keys) {
            json[key] = base[key]
        }
        json["mySeat"] = seatNumber
        json["spectators"] = Json.Array(getSpectators().map { it.name })
        json["playerStatuses"] = getSeatStatuses()
        return json
    }

    /**
     * Send event to a specific seat (all connections of the user in that seat).
     */
    fun sendToSeat(seatNumber: Int, event: String, data: Json.Object) {
        seats.getOrNull(seatNumber)?.user?.let { user ->
            sendToUser(user, event, data)
        }
    }

    /**
     * Broadcast to all seated players (not spectators).
     */
    fun broadcastToSeated(event: String, data: Json.Object) {
        seats.drop(1).forEach { seat ->
            seat.user?.let { user ->
                sendToUser(user, event, data)
            }
        }
    }

    /**
     * Update player status for a seat.
     * @param seatNumber The seat to update
     * @param newStatus The new status
     * @param broadcastChange Whether to broadcast the status change to all participants
     */
    fun updatePlayerStatus(seatNumber: Int, newStatus: PlayerStatus, broadcastChange: Boolean = true) {
        val seat = getSeat(seatNumber) ?: return
        if (seat.status == newStatus) return  // No change
        seat.status = newStatus
        if (broadcastChange) {
            broadcast("player_status", Json.Object(
                "seat" to seatNumber,
                "player" to seat.playerName,
                "status" to newStatus.name.lowercase()
            ))
        }
    }

    /**
     * Update player status by user.
     */
    fun updatePlayerStatus(user: User, newStatus: PlayerStatus) {
        val seat = getSeatForUser(user) ?: return
        updatePlayerStatus(seat.number, newStatus)
    }

    /**
     * Get all seat statuses (for state sync).
     */
    fun getSeatStatuses(): Json.Array = Json.Array(seats.drop(1).filter { !it.isEmpty }.map { seat ->
        Json.Object(
            "seat" to seat.number,
            "player" to seat.playerName,
            "status" to seat.status.name.lowercase()
        )
    })

    override suspend fun onActorJoined(actor: Actor) {
        val seatNumber = assignActorToSeat(actor)
        if (seatNumber != null) {
            onPlayerSeated(actor, seatNumber)
        } else {
            onSpectatorJoined(actor as? Spectator ?: Spectator(actor.connectionId, actor.user, actor.name))
        }
    }

    override suspend fun onActorLeft(actor: Actor, userFullyLeft: Boolean) {
        val user = actor.user
        if (user != null) {
            val seat = getSeatForUser(user)
            if (seat != null) {
                if (userFullyLeft) {
                    // User has no more connections - mark as offline
                    disconnectSeat(user)
                    onPlayerDisconnected(actor, seat.number)
                } else {
                    // User still has other connections - just a tab closing
                    onPlayerConnectionClosed(actor, seat.number)
                }
                return
            }
        }
        // Not a seated player - must be spectator behavior from parent
    }

    /**
     * Handle a status action from a client.
     * Call this from handleAction when action type is "status".
     * @return ActionResult.Success if handled, null if not a status action
     */
    protected fun handleStatusAction(actor: Actor, action: Json.Object): ActionResult? {
        if (action.getString("type") != "status") return null
        val statusStr = action.getString("status") ?: return ActionResult.Error("Missing status field")
        val newStatus = try {
            PlayerStatus.valueOf(statusStr.uppercase())
        } catch (e: IllegalArgumentException) {
            return ActionResult.Error("Invalid status: $statusStr")
        }
        // Don't allow clients to set OFFLINE (that's server-controlled)
        if (newStatus == PlayerStatus.OFFLINE) {
            return ActionResult.Error("Cannot set OFFLINE status")
        }
        // Use user to find seat
        val user = actor.user ?: return ActionResult.Error("Not authenticated")
        val seat = getSeatForUser(user) ?: return ActionResult.Error("Player not found")
        updatePlayerStatus(seat.number, newStatus)
        return ActionResult.Success()
    }

    /**
     * Called when a player takes a seat.
     */
    protected open suspend fun onPlayerSeated(actor: Actor, seatNumber: Int) {}

    /**
     * Called when a seated player fully disconnects (no more connections).
     * Seat is retained for reconnect.
     */
    protected open suspend fun onPlayerDisconnected(actor: Actor, seatNumber: Int) {}

    /**
     * Called when one of a player's connections closes, but they still have other connections.
     * Default: no-op. Override if you need to track individual connection closures.
     */
    protected open suspend fun onPlayerConnectionClosed(actor: Actor, seatNumber: Int) {}

    override fun toJson(): Json.Object = Json.Object(
        "id" to id,
        "seatCount" to seatCount,
        "seats" to Json.Array(seats.drop(1).map { seat ->
            Json.Object(
                "number" to seat.number,
                "player" to seat.playerName,
                "userId" to seat.user?.userId,
                "connected" to seat.isConnected(id),
                "status" to seat.status.name.lowercase()
            )
        }),
        "userCount" to users.size,
        "spectatorCount" to spectatorCount.value
    )

    // ========== Legacy compatibility ==========

    /**
     * @deprecated Use getSeatForUser(user) instead
     */
    @Deprecated("Use getSeatForUser(user) instead")
    fun getSeatForConnection(connectionId: String): Seat? {
        // Find the actor, then find their seat via user
        val actor = getActor(connectionId) ?: return null
        return actor.user?.let { getSeatForUser(it) }
    }
}
