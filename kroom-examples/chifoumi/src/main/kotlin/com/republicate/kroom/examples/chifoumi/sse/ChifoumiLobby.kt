package com.republicate.kroom.examples.chifoumi.sse

import com.republicate.kroom.examples.chifoumi.game.Match
import com.republicate.kroom.examples.chifoumi.game.MatchStats
import com.republicate.kroom.examples.chifoumi.game.Move
import com.republicate.kroom.examples.chifoumi.game.Round
import com.republicate.kroom.server.ActionResult
import com.republicate.kroom.server.Actor
import com.republicate.kroom.server.Room
import com.republicate.kson.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Lobby state
 */
data class LobbyState(
    val queueSize: Int = 0,
    val activeMatchCount: Int = 0
)

/**
 * Main lobby room for Chifoumi Arena.
 *
 * Handles:
 * - Player presence (who's online)
 * - Matchmaking queue
 * - Activity feed (recent match results)
 * - Active matches
 */
object ChifoumiLobby : Room<LobbyState>("chifoumi-lobby") {
    private val log = LoggerFactory.getLogger("chifoumi.lobby")

    override var state = LobbyState()

    // Players waiting for a match
    private val matchQueue = ConcurrentLinkedQueue<String>()

    // Active matches by ID
    private val activeMatches = ConcurrentHashMap<String, Match>()

    // Player -> Match ID mapping
    private val playerMatches = ConcurrentHashMap<String, String>()

    // Recent activity (last 10 events)
    private val activityFeed = ConcurrentLinkedQueue<ActivityEvent>()
    private const val MAX_ACTIVITY = 10

    // Actor ID -> login name mapping (for targeted sends)
    private val actorLogins = ConcurrentHashMap<String, Actor>()

    override fun stateToJson(): Json.Object = Json.Object(
        "queue" to matchQueue.size,
        "matches" to activeMatches.size,
        "stats" to MatchStats.toJson(),
        "activity" to Json.Array(activityFeed.map { it.toJson() })
    )

    override fun handleAction(actor: Actor, action: Json.Object): ActionResult {
        val type = action.getString("type") ?: return ActionResult.Error("Missing action type")
        return when (type) {
            "join_queue" -> handleJoinQueue(actor)
            "leave_queue" -> handleLeaveQueue(actor)
            "play" -> {
                val moveStr = action.getString("move") ?: return ActionResult.Error("Missing move")
                val move = Move.fromString(moveStr) ?: return ActionResult.Error("Invalid move: $moveStr")
                handlePlayMove(actor, move)
            }
            else -> ActionResult.Error("Unknown action: $type")
        }
    }

    private fun handleJoinQueue(actor: Actor): ActionResult {
        val login = actor.name
        // Already in queue?
        if (matchQueue.contains(login)) return ActionResult.Success()

        // Already in match?
        if (playerMatches.containsKey(login)) return ActionResult.Success()

        // Try to find opponent
        val opponent = matchQueue.poll()
        return if (opponent != null && opponent != login) {
            // Create match
            val match = Match(player1 = opponent, player2 = login)
            activeMatches[match.id] = match
            playerMatches[opponent] = match.id
            playerMatches[login] = match.id
            updateState()

            // Notify both players
            actorLogins[opponent]?.let { sendTo(it, "match", match.toJson()) }
            sendTo(actor, "match", match.toJson())

            addActivity(ActivityEvent.MatchStarted(opponent, login, match.id))
            log.info("Match ${match.id} started: $opponent vs $login")
            ActionResult.Success("matched", Json.Object("match" to match.toJson()))
        } else {
            // Add to queue
            matchQueue.add(login)
            updateState()
            sendTo(actor, "queue", Json.Object("position" to matchQueue.size))
            log.info("$login joined queue (position: ${matchQueue.size})")
            ActionResult.Success()
        }
    }

    private fun handleLeaveQueue(actor: Actor): ActionResult {
        matchQueue.remove(actor.name)
        updateState()
        return ActionResult.Success()
    }

    private fun handlePlayMove(actor: Actor, move: Move): ActionResult {
        val login = actor.name
        val matchId = playerMatches[login] ?: return ActionResult.Error("Not in a match")
        val match = activeMatches[matchId] ?: return ActionResult.Error("Match not found")

        if (match.isFinished) return ActionResult.Error("Match already finished")

        return try {
            val round = match.play(login, move)
            MatchStats.recordMove(move)

            if (round != null) {
                // Round complete, notify both players
                val opponent = if (match.player1 == login) match.player2 else match.player1
                val roundData = Json.Object("round" to round.toJson())
                sendTo(actor, "round", roundData)
                actorLogins[opponent]?.let { sendTo(it, "round", roundData) }

                // Check if match is finished
                if (match.isFinished) {
                    MatchStats.recordMatch()
                    val winner = match.winner!!
                    val loser = if (winner == match.player1) match.player2 else match.player1
                    addActivity(ActivityEvent.MatchEnded(winner, loser, match.id, match.score1, match.score2))

                    // Send final result
                    val resultData = Json.Object("match" to match.toJson())
                    sendTo(actor, "result", resultData)
                    actorLogins[opponent]?.let { sendTo(it, "result", resultData) }

                    endMatch(matchId)
                }
                ActionResult.Success()
            } else {
                // Waiting for opponent
                val opponent = if (match.player1 == login) match.player2 else match.player1
                actorLogins[opponent]?.let { sendTo(it, "waiting", Json.Object("player" to login)) }
                ActionResult.Success()
            }
        } catch (e: IllegalArgumentException) {
            ActionResult.Error(e.message ?: "Invalid move")
        }
    }

    override suspend fun onActorJoined(actor: Actor) {
        actorLogins[actor.name] = actor

        // Send current match if player is in one
        playerMatches[actor.name]?.let { matchId ->
            activeMatches[matchId]?.let { match ->
                sendTo(actor, "match", match.toJson())
            }
        }

        addActivity(ActivityEvent.PlayerJoined(actor.name))
    }

    override suspend fun onActorLeft(actor: Actor) {
        val login = actor.name
        actorLogins.remove(login)

        // Remove from queue if waiting
        matchQueue.remove(login)
        updateState()

        // Handle if in active match
        playerMatches[login]?.let { matchId ->
            activeMatches[matchId]?.let { match ->
                val opponent = if (match.player1 == login) match.player2 else match.player1
                addActivity(ActivityEvent.PlayerLeft(login, opponent))
                endMatch(matchId)
            }
        }
        addActivity(ActivityEvent.PlayerDisconnected(login))
    }

    override fun shouldCloseWhenEmpty(): Boolean = false

    /**
     * Player joins the matchmaking queue (called from HTTP API).
     * @return Match if paired, null if waiting
     */
    @Synchronized
    fun joinQueue(login: String): Match? {
        // Already in queue?
        if (matchQueue.contains(login)) return null

        // Already in match?
        if (playerMatches.containsKey(login)) return null

        // Try to find opponent
        val opponent = matchQueue.poll()
        return if (opponent != null && opponent != login) {
            // Create match
            val match = Match(player1 = opponent, player2 = login)
            activeMatches[match.id] = match
            playerMatches[opponent] = match.id
            playerMatches[login] = match.id
            updateState()

            // Notify both players via SSE
            actorLogins[opponent]?.let { sendTo(it, "match", match.toJson()) }
            actorLogins[login]?.let { sendTo(it, "match", match.toJson()) }

            addActivity(ActivityEvent.MatchStarted(opponent, login, match.id))
            log.info("Match ${match.id} started: $opponent vs $login")
            match
        } else {
            // Add to queue
            matchQueue.add(login)
            updateState()
            actorLogins[login]?.let { sendTo(it, "queue", Json.Object("position" to matchQueue.size)) }
            log.info("$login joined queue (position: ${matchQueue.size})")
            null
        }
    }

    /**
     * Player leaves the matchmaking queue.
     */
    fun leaveQueue(login: String) {
        matchQueue.remove(login)
        updateState()
    }

    /**
     * Get player's current match.
     */
    fun getMatch(login: String): Match? {
        return playerMatches[login]?.let { activeMatches[it] }
    }

    /**
     * Get match by ID.
     */
    fun getMatchById(matchId: String): Match? = activeMatches[matchId]

    /**
     * Player submits a move (called from HTTP API).
     * @return the completed Round if both played, null otherwise
     */
    fun playMove(login: String, move: Move): PlayResult {
        val matchId = playerMatches[login] ?: return PlayResult.NotInMatch
        val match = activeMatches[matchId] ?: return PlayResult.NotInMatch

        if (match.isFinished) return PlayResult.MatchFinished

        return try {
            val round = match.play(login, move)
            MatchStats.recordMove(move)

            if (round != null) {
                // Round complete, notify both players
                val opponent = if (match.player1 == login) match.player2 else match.player1
                val roundData = Json.Object("round" to round.toJson())
                actorLogins[login]?.let { sendTo(it, "round", roundData) }
                actorLogins[opponent]?.let { sendTo(it, "round", roundData) }

                // Check if match is finished
                if (match.isFinished) {
                    MatchStats.recordMatch()
                    val winner = match.winner!!
                    val loser = if (winner == match.player1) match.player2 else match.player1
                    addActivity(ActivityEvent.MatchEnded(winner, loser, match.id, match.score1, match.score2))

                    // Send final result
                    val resultData = Json.Object("match" to match.toJson())
                    actorLogins[login]?.let { sendTo(it, "result", resultData) }
                    actorLogins[opponent]?.let { sendTo(it, "result", resultData) }

                    endMatch(matchId)
                    PlayResult.MatchComplete(round, match)
                } else {
                    PlayResult.RoundComplete(round)
                }
            } else {
                // Waiting for opponent
                val opponent = if (match.player1 == login) match.player2 else match.player1
                actorLogins[opponent]?.let { sendTo(it, "waiting", Json.Object("player" to login)) }
                PlayResult.WaitingForOpponent
            }
        } catch (e: IllegalArgumentException) {
            PlayResult.InvalidMove(e.message ?: "Invalid move")
        }
    }

    private fun endMatch(matchId: String) {
        val match = activeMatches.remove(matchId)
        match?.let {
            playerMatches.remove(it.player1)
            playerMatches.remove(it.player2)
        }
        updateState()
    }

    private fun addActivity(event: ActivityEvent) {
        activityFeed.add(event)
        while (activityFeed.size > MAX_ACTIVITY) {
            activityFeed.poll()
        }
        broadcast("activity", event.toJson())
    }

    private fun updateState() {
        state = LobbyState(
            queueSize = matchQueue.size,
            activeMatchCount = activeMatches.size
        )
    }

    fun getQueueSize() = matchQueue.size
    fun getActiveMatchCount() = activeMatches.size
}

sealed class PlayResult {
    data object NotInMatch : PlayResult()
    data object MatchFinished : PlayResult()
    data object WaitingForOpponent : PlayResult()
    data class InvalidMove(val reason: String) : PlayResult()
    data class RoundComplete(val round: Round) : PlayResult()
    data class MatchComplete(val round: Round, val match: Match) : PlayResult()
}

sealed class ActivityEvent {
    abstract fun toJson(): Json.Object

    data class PlayerJoined(val player: String) : ActivityEvent() {
        override fun toJson() = Json.MutableObject().apply {
            set("type", "joined")
            set("player", player)
            set("time", System.currentTimeMillis())
        }
    }

    data class PlayerDisconnected(val player: String) : ActivityEvent() {
        override fun toJson() = Json.MutableObject().apply {
            set("type", "disconnected")
            set("player", player)
            set("time", System.currentTimeMillis())
        }
    }

    data class PlayerLeft(val player: String, val opponent: String) : ActivityEvent() {
        override fun toJson() = Json.MutableObject().apply {
            set("type", "left")
            set("player", player)
            set("opponent", opponent)
            set("time", System.currentTimeMillis())
        }
    }

    data class MatchStarted(val player1: String, val player2: String, val matchId: String) : ActivityEvent() {
        override fun toJson() = Json.MutableObject().apply {
            set("type", "match_started")
            set("player1", player1)
            set("player2", player2)
            set("matchId", matchId)
            set("time", System.currentTimeMillis())
        }
    }

    data class MatchEnded(val winner: String, val loser: String, val matchId: String, val score1: Int, val score2: Int) : ActivityEvent() {
        override fun toJson() = Json.MutableObject().apply {
            set("type", "match_ended")
            set("winner", winner)
            set("loser", loser)
            set("matchId", matchId)
            set("score", "$score1-$score2")
            set("time", System.currentTimeMillis())
        }
    }
}
