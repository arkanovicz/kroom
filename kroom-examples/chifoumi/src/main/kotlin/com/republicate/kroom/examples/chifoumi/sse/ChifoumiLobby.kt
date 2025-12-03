package com.republicate.kroom.examples.chifoumi.sse

import com.republicate.kroom.examples.chifoumi.game.Match
import com.republicate.kroom.examples.chifoumi.game.MatchStats
import com.republicate.kroom.examples.chifoumi.game.Move
import com.republicate.kroom.examples.chifoumi.game.Round
import com.republicate.kroom.server.Room
import com.republicate.kroom.server.User
import com.republicate.kson.Json
import io.ktor.sse.*
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Main lobby room for Chifoumi Arena.
 *
 * Handles:
 * - Player presence (who's online)
 * - Matchmaking queue
 * - Activity feed (recent match results)
 * - Active matches
 */
object ChifoumiLobby : Room("chifoumi-lobby") {
    private val logger = LoggerFactory.getLogger("chifoumi.lobby")

    // Players waiting for a match
    private val matchQueue = ConcurrentLinkedQueue<String>()

    // Active matches by ID
    private val activeMatches = ConcurrentHashMap<String, Match>()

    // Player -> Match ID mapping
    private val playerMatches = ConcurrentHashMap<String, String>()

    // Recent activity (last 10 events)
    private val activityFeed = ConcurrentLinkedQueue<ActivityEvent>()
    private const val MAX_ACTIVITY = 10

    override suspend fun sendPrivateContextToChannel(user: User, channel: Channel<ServerSentEvent>) {
        // Send current match if player is in one
        playerMatches[user.login]?.let { matchId ->
            activeMatches[matchId]?.let { match ->
                channel.send(ServerSentEvent(
                    data = match.toJson().toString(),
                    event = "match"
                ))
            }
        }

        // Send activity feed
        activityFeed.forEach { event ->
            channel.send(ServerSentEvent(
                data = event.toJson().toString(),
                event = "activity"
            ))
        }

        // Send stats
        channel.send(ServerSentEvent(
            data = MatchStats.toJson().toString(),
            event = "stats"
        ))
    }

    override suspend fun onUserJoined(login: String) {
        super.onUserJoined(login)
        addActivity(ActivityEvent.PlayerJoined(login))
    }

    override suspend fun onUserLeft(login: String) {
        super.onUserLeft(login)
        // Remove from queue if waiting
        matchQueue.remove(login)
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

    /**
     * Player joins the matchmaking queue.
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

            // Notify both players
            post(opponent, "match", match.toJson().toString())
            post(login, "match", match.toJson().toString())

            addActivity(ActivityEvent.MatchStarted(opponent, login, match.id))
            logger.info("Match ${match.id} started: $opponent vs $login")
            match
        } else {
            // Add to queue
            matchQueue.add(login)
            post(login, "queue", """{"position":${matchQueue.size}}""")
            logger.info("$login joined queue (position: ${matchQueue.size})")
            null
        }
    }

    /**
     * Player leaves the matchmaking queue.
     */
    fun leaveQueue(login: String) {
        matchQueue.remove(login)
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
     * Player submits a move.
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
                val roundJson = round.toJson().toString()
                post(login, "round", roundJson)
                post(opponent, "round", roundJson)

                // Check if match is finished
                if (match.isFinished) {
                    MatchStats.recordMatch()
                    val winner = match.winner!!
                    val loser = if (winner == match.player1) match.player2 else match.player1
                    addActivity(ActivityEvent.MatchEnded(winner, loser, match.id, match.score1, match.score2))

                    // Send final result
                    val resultJson = match.toJson().toString()
                    post(login, "result", resultJson)
                    post(opponent, "result", resultJson)

                    endMatch(matchId)
                    PlayResult.MatchComplete(round, match)
                } else {
                    PlayResult.RoundComplete(round)
                }
            } else {
                // Waiting for opponent
                val opponent = if (match.player1 == login) match.player2 else match.player1
                post(opponent, "waiting", """{"player":"$login"}""")
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
    }

    private fun addActivity(event: ActivityEvent) {
        activityFeed.add(event)
        while (activityFeed.size > MAX_ACTIVITY) {
            activityFeed.poll()
        }
        post("activity", event.toJson().toString())
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
