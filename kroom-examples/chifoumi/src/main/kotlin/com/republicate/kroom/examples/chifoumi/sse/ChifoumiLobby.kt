package com.republicate.kroom.examples.chifoumi.sse

import com.republicate.kroom.examples.chifoumi.game.Match
import com.republicate.kroom.examples.chifoumi.game.MatchStats
import com.republicate.kroom.examples.chifoumi.game.Move
import com.republicate.kroom.server.ActionResult
import com.republicate.kroom.server.Actor
import com.republicate.kroom.server.Room
import com.republicate.kroom.server.User
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
 * Main lobby room for Chifoumi Arena: presence, matchmaking queue, activity feed, active matches.
 * Every move of a player is an intent handled here, on the room's loop; the routes only forward.
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

    // Player name -> identity: a frame for a player reaches every tab of theirs
    private val players = ConcurrentHashMap<String, User>()

    override fun shouldCloseWhenEmpty(): Boolean = false

    override fun stateToJson(): Json.Object = Json.Object(
        "queue" to matchQueue.size,
        "matches" to activeMatches.size,
        "stats" to MatchStats.toJson(),
        "activity" to Json.Array(activityFeed.map { it.toJson() })
    )

    override fun handleAction(actor: Actor, action: Json.Object): ActionResult {
        return when (val type = action.getString("type")) {
            "join_queue" -> joinQueue(actor.name)
            "leave_queue" -> {
                matchQueue.remove(actor.name)
                updateState()
                ActionResult.Success()
            }
            "play" -> {
                val moveStr = action.getString("move") ?: return ActionResult.Error("Missing move")
                val move = Move.fromString(moveStr) ?: return ActionResult.Error("Invalid move: $moveStr")
                play(actor.name, move)
            }
            else -> ActionResult.Error("Unknown action: $type")
        }
    }

    private fun tell(login: String, event: String, data: Json.Object) {
        players[login]?.let { sendToUser(it, event, data) }
    }

    private fun joinQueue(login: String): ActionResult {
        if (matchQueue.contains(login) || playerMatches.containsKey(login)) return ActionResult.Success()

        val opponent = matchQueue.poll()
        if (opponent != null && opponent != login) {
            val match = Match(player1 = opponent, player2 = login)
            activeMatches[match.id] = match
            playerMatches[opponent] = match.id
            playerMatches[login] = match.id
            updateState()
            tell(opponent, "match", match.toJson())
            tell(login, "match", match.toJson())
            addActivity(ActivityEvent.MatchStarted(opponent, login, match.id))
            log.info("Match ${match.id} started: $opponent vs $login")
        } else {
            matchQueue.add(login)
            updateState()
            tell(login, "queue", Json.Object("position" to matchQueue.size))
            log.info("$login joined queue (position: ${matchQueue.size})")
        }
        return ActionResult.Success()
    }

    private fun play(login: String, move: Move): ActionResult {
        val matchId = playerMatches[login] ?: return ActionResult.Error("Not in a match")
        val match = activeMatches[matchId] ?: return ActionResult.Error("Match not found")
        if (match.isFinished) return ActionResult.Error("Match already finished")
        val opponent = if (match.player1 == login) match.player2 else match.player1

        val round = try {
            match.play(login, move)
        } catch (e: IllegalArgumentException) {
            return ActionResult.Error(e.message ?: "Invalid move")
        }
        MatchStats.recordMove(move)

        if (round == null) {
            tell(opponent, "waiting", Json.Object("player" to login))
            return ActionResult.Success()
        }

        val roundData = Json.Object("round" to round.toJson())
        tell(login, "round", roundData)
        tell(opponent, "round", roundData)

        if (match.isFinished) {
            MatchStats.recordMatch()
            val winner = match.winner!!
            val loser = if (winner == match.player1) match.player2 else match.player1
            addActivity(ActivityEvent.MatchEnded(winner, loser, match.id, match.score1, match.score2))
            val resultData = Json.Object("match" to match.toJson())
            tell(login, "result", resultData)
            tell(opponent, "result", resultData)
            endMatch(matchId)
        }
        return ActionResult.Success()
    }

    override suspend fun onActorJoined(actor: Actor) {
        val user = actor.user ?: return
        players[actor.name] = user

        // this tab resumes its match, if any
        playerMatches[actor.name]?.let { matchId ->
            activeMatches[matchId]?.let { match -> sendTo(actor, "match", match.toJson()) }
        }

        // the first tab of the player is the arrival; a second tab, a reconnect are not
        if (user.getConnectionsInRoom(id).size == 1) addActivity(ActivityEvent.PlayerJoined(actor.name))
    }

    override suspend fun onActorLeft(actor: Actor, userFullyLeft: Boolean) {
        if (!userFullyLeft) return
        val login = actor.name
        players.remove(login)

        matchQueue.remove(login)
        updateState()

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
     * Player's current match.
     */
    fun getMatch(login: String): Match? {
        return playerMatches[login]?.let { activeMatches[it] }
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
