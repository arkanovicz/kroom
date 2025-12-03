package com.republicate.kroom.examples.chifoumi.game

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * A match between two players, best of N rounds.
 */
class Match(
    val id: String = UUID.randomUUID().toString().take(8),
    val player1: String,
    val player2: String,
    val bestOf: Int = 3
) {
    private val rounds = mutableListOf<Round>()
    private var currentRound: Round? = null

    val score1: Int get() = rounds.count { it.winner == player1 }
    val score2: Int get() = rounds.count { it.winner == player2 }

    val isFinished: Boolean get() {
        val winsNeeded = (bestOf / 2) + 1
        return score1 >= winsNeeded || score2 >= winsNeeded
    }

    val winner: String? get() = when {
        !isFinished -> null
        score1 > score2 -> player1
        else -> player2
    }

    val roundNumber: Int get() = rounds.size + 1

    /**
     * Submit a move for a player in the current round.
     * @return the completed Round if both players have moved, null otherwise
     */
    @Synchronized
    fun play(player: String, move: Move): Round? {
        require(player == player1 || player == player2) { "Unknown player: $player" }
        require(!isFinished) { "Match is already finished" }

        if (currentRound == null) {
            currentRound = Round(roundNumber)
        }

        val round = currentRound!!
        if (player == player1) {
            require(round.move1 == null) { "Player 1 already played" }
            round.move1 = move
        } else {
            require(round.move2 == null) { "Player 2 already played" }
            round.move2 = move
        }

        // Check if round is complete
        return if (round.move1 != null && round.move2 != null) {
            round.resolve(player1, player2)
            rounds.add(round)
            currentRound = null
            round
        } else {
            null
        }
    }

    /**
     * Check if a player has already played this round.
     */
    fun hasPlayed(player: String): Boolean {
        val round = currentRound ?: return false
        return if (player == player1) round.move1 != null else round.move2 != null
    }

    fun toJson() = com.republicate.kson.Json.MutableObject().apply {
        set("id", id)
        set("player1", player1)
        set("player2", player2)
        set("score1", score1)
        set("score2", score2)
        set("round", roundNumber)
        set("bestOf", bestOf)
        set("finished", isFinished)
        winner?.let { set("winner", it) }
    }
}

/**
 * A single round in a match.
 */
class Round(val number: Int) {
    var move1: Move? = null
    var move2: Move? = null
    var winner: String? = null
    var result: Int = 0 // 1 = player1 wins, -1 = player2 wins, 0 = draw

    fun resolve(player1: String, player2: String) {
        val m1 = move1 ?: error("Move 1 not set")
        val m2 = move2 ?: error("Move 2 not set")
        result = m1.against(m2)
        winner = when (result) {
            1 -> player1
            -1 -> player2
            else -> null
        }
    }

    fun toJson(forPlayer: String? = null) = com.republicate.kson.Json.MutableObject().apply {
        set("number", number)
        set("result", result)
        winner?.let { set("winner", it) }
        // Only reveal moves after resolution
        if (move1 != null && move2 != null) {
            set("move1", move1!!.name.lowercase())
            set("move2", move2!!.name.lowercase())
        }
    }
}

/**
 * Global match counter for stats.
 */
object MatchStats {
    private val matchesPlayed = AtomicInteger(0)
    private val moveCounts = Move.entries.associateWith { AtomicInteger(0) }

    fun recordMatch() = matchesPlayed.incrementAndGet()
    fun recordMove(move: Move) = moveCounts[move]?.incrementAndGet()

    fun toJson() = com.republicate.kson.Json.MutableObject().apply {
        set("matchesPlayed", matchesPlayed.get())
        set("moves", com.republicate.kson.Json.MutableObject().apply {
            moveCounts.forEach { (move, count) ->
                set(move.name.lowercase(), count.get())
            }
        })
    }
}
