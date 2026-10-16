package com.republicate.kroom.examples.chifoumi.game

import com.republicate.kson.Json
import java.util.UUID

/**
 * A match as the rule plays it, two players (by user id), first to [target] won rounds; draws
 * count for nobody. The move of a round in progress lives here and nowhere on the wire; what the
 * wire gets is [toJson], a `MatchView`.
 */
class Game(
    val player1: String,
    val player2: String,
    val name1: String,
    val name2: String,
    val target: Int = 5,
    val id: String = UUID.randomUUID().toString().take(8)
) {
    private val rounds = mutableListOf<GameRound>()
    private var currentRound: GameRound? = null
    private var forfeited: String? = null

    val score1: Int get() = rounds.count { it.winner == player1 }
    val score2: Int get() = rounds.count { it.winner == player2 }

    val isFinished: Boolean get() = forfeited != null || score1 >= target || score2 >= target

    val winner: String? get() = when {
        forfeited != null -> opponentOf(forfeited!!)
        !isFinished -> null
        score1 > score2 -> player1
        else -> player2
    }

    val roundNumber: Int get() = rounds.size + 1

    fun has(player: String) = player == player1 || player == player2
    fun opponentOf(player: String) = if (player == player1) player2 else player1

    /** A move for the round in progress; the round once both have played, null while one is awaited. */
    fun play(player: String, move: Move): GameRound? {
        require(has(player)) { "Unknown player: $player" }
        require(!isFinished) { "Match is already finished" }
        val round = currentRound ?: GameRound(roundNumber).also { currentRound = it }
        if (player == player1) { require(round.move1 == null) { "Player 1 already played" }; round.move1 = move }
        else { require(round.move2 == null) { "Player 2 already played" }; round.move2 = move }
        if (round.move1 == null || round.move2 == null) return null
        round.resolve(player1, player2)
        rounds.add(round)
        currentRound = null
        return round
    }

    /** A move taken back while the other is awaited. */
    fun withdraw(player: String) {
        val round = currentRound ?: return
        if (player == player1) round.move1 = null else round.move2 = null
    }

    fun hasPlayed(player: String): Boolean {
        val round = currentRound ?: return false
        return if (player == player1) round.move1 != null else round.move2 != null
    }

    /** The match ends, the other wins. */
    fun forfeit(player: String) { if (!isFinished) forfeited = player }

    fun toJson() = Json.MutableObject().apply {
        set("id", id)
        set("player1", player1)
        set("player2", player2)
        set("name1", name1)
        set("name2", name2)
        set("score1", score1)
        set("score2", score2)
        set("round", roundNumber)
        set("target", target)
        set("finished", isFinished)
        winner?.let { set("winner", it) }
    }
}

/** A single round of a game. */
class GameRound(val number: Int) {
    var move1: Move? = null
    var move2: Move? = null
    var winner: String? = null
    var result: Int = 0 // 1 = player1 wins, -1 = player2 wins, 0 = draw

    fun resolve(player1: String, player2: String) {
        val m1 = move1 ?: error("Move 1 not set")
        val m2 = move2 ?: error("Move 2 not set")
        result = m1.against(m2)
        winner = when (result) { 1 -> player1; -1 -> player2; else -> null }
    }

    /** The round once resolved: both moves are public then. */
    fun toJson() = Json.MutableObject().apply {
        set("number", number)
        set("result", result)
        winner?.let { set("winner", it) }
        set("move1", move1!!.name.lowercase())
        set("move2", move2!!.name.lowercase())
    }
}
