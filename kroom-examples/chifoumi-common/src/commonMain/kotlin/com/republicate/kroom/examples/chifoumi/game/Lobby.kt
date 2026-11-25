package com.republicate.kroom.examples.chifoumi.game

import com.republicate.kroom.event.Cancel
import com.republicate.kroom.event.Chat
import com.republicate.kroom.event.ChatEvents
import com.republicate.kroom.event.ChatState
import com.republicate.kroom.event.Event
import com.republicate.kroom.event.JsonView
import com.republicate.kroom.event.Reason
import com.republicate.kroom.event.Leave
import com.republicate.kson.Json

/** The lobby's alphabet: the chat's, the game's intents, the round the logic emits; kroom's beside. */
object ChifoumiEvents : ChatEvents<Chat>(::Chat) {
    val match = intent(::Match, cancellable = true)
    val play = intent(::Play, cancellable = true)
    val round = event(::Round)
}

/** A user as the lobby lists one: who waits, who has played the round. */
class Player(json: Json.MutableObject = Json.MutableObject()) : JsonView(json) {
    val id by string().required
    val name by string()
}

/** A match: scores, round, winner, and the moves of the round in progress, each seen by its player alone. */
class MatchView(json: Json.MutableObject = Json.MutableObject()) : JsonView(json) {
    val id by string().required
    val player1 by string().required
    val player2 by string().required
    val name1 by string()
    val name2 by string()
    val score1 by int()
    val score2 by int()
    val round by int()
    val target by int()
    val finished by bool()
    val winner by string()
    /** Who has played the round in progress, never which move: the fold of `play` and its `cancel`; the `round` frame's match starts afresh. */
    val played by keyed(::Player)
    var move1 by enum<Move>().visibleTo { it == player1 }
        internal set
    var move2 by enum<Move>().visibleTo { it == player2 }
        internal set

    fun has(user: String) = user == player1 || user == player2
    fun opponentOf(user: String) = if (user == player1) player2 else player1
    fun moveOf(user: String): Move? = if (user == player1) move1 else move2

    /** The round [user] completes by playing [move] against the move awaiting it, and the match after it; null while the other has not played. */
    fun resolve(user: String, move: Move): Pair<MatchView, Json.Object>? {
        val other = moveOf(opponentOf(user)) ?: return null
        val (m1, m2) = if (user == player1) move to other else other to move
        val result = m1.against(m2)
        val winner = when (result) { 1 -> player1; -1 -> player2; else -> null }
        val s1 = (score1 ?: 0) + if (result == 1) 1 else 0
        val s2 = (score2 ?: 0) + if (result == -1) 1 else 0
        val number = round ?: 1
        val over = s1 >= (target ?: TARGET) || s2 >= (target ?: TARGET)
        val next = next(s1, s2, number + 1, over, if (over) (if (s1 > s2) player1 else player2) else null)
        val played = Json.MutableObject("number" to number, "result" to result, "move1" to m1.name.lowercase(), "move2" to m2.name.lowercase())
        winner?.let { played["winner"] = it }
        return next to played
    }

    /** The match ended by [user]'s departure: the other wins. */
    fun forfeit(user: String): MatchView = next(score1 ?: 0, score2 ?: 0, round ?: 1, true, opponentOf(user))

    private fun next(s1: Int, s2: Int, round: Int, finished: Boolean, winner: String?) = MatchView(Json.MutableObject(
        "id" to id, "player1" to player1, "player2" to player2, "name1" to name1, "name2" to name2,
        "score1" to s1, "score2" to s2, "round" to round, "target" to (target ?: TARGET), "finished" to finished
    ).also { j -> winner?.let { j["winner"] = it } })

    companion object {
        const val TARGET = 5

        /** A match starting between two players, first to [target] won rounds; draws count for nobody. */
        fun start(id: String, p1: Player, p2: Player, target: Int = TARGET) = MatchView(Json.MutableObject(
            "id" to id, "player1" to p1.id, "player2" to p2.id, "name1" to (p1.name ?: p1.id), "name2" to (p2.name ?: p2.id),
            "score1" to 0, "score2" to 0, "round" to 1, "target" to target, "finished" to false))
    }
}

/** What the rounds add up to: the fold's own view, counted from `round` frames. */
class Stats(json: Json.MutableObject = Json.MutableObject()) : JsonView(json) {
    var played by int()
        private set
    var rock by int()
        private set
    var paper by int()
        private set
    var scissors by int()
        private set
    var well by int()
        private set

    fun count(round: Json.Object) {
        listOfNotNull(round.getString("move1"), round.getString("move2")).forEach {
            when (it) { "rock" -> rock = (rock ?: 0) + 1; "paper" -> paper = (paper ?: 0) + 1; "scissors" -> scissors = (scissors ?: 0) + 1; "well" -> well = (well ?: 0) + 1 }
        }
    }

    /** A match over, won or forfeited. */
    fun ended() { played = (played ?: 0) + 1 }
}

/** Wanting a match: a deposit in the queue while nobody else waits, paired with the first who does. `leave` ends a match the same way, forfeited. */
class Match(json: Json.MutableObject = Json.MutableObject()) : Event("match", json = json) {
    var player by obj(::Player).out     // who wants one (a deposit); absent when the room ends a match
    var match by obj(::MatchView).out   // the match, when one starts or ends
    init {
        check { state: ChifoumiState, actor ->
            when {
                state.myMatch(actor.id) != null -> Reason("in_match")
                actor.id in state.queue -> Reason("in_queue")
                else -> null
            }
        }
    }
}

/** A move: its player alone sees which (it goes into the match so); everyone sees who played. A deposit until the other plays. */
class Play(json: Json.MutableObject = Json.MutableObject()) : Event("play", json = json) {
    val move by enum<Move>().required.visibleTo { it == player }
    var player by string().out
    init {
        check { state: ChifoumiState, actor ->
            val match = state.myMatch(actor.id)
            when {
                match == null -> Reason("not_in_match")
                actor.id in match.played -> Reason("already_played")
                else -> null
            }
        }
    }
}

/** Both have played: the round, resolved, and the match as it stands. */
class Round(json: Json.MutableObject = Json.MutableObject()) : Event("round", json = json) {
    var match by obj(::MatchView).required.out
    var round by obj().required.out
}

/** The lobby's State: the chat's tail, who waits, the matches running, the totals. */
class ChifoumiState(json: Json.MutableObject = Json.MutableObject()) : ChatState<Chat>(json, line = ::Chat) {
    val queue by keyed(::Player)
    val matches by keyed(::MatchView)
    val stats by obj(::Stats)

    init { if (json["stats"] == null) json["stats"] = Json.MutableObject("played" to 0, "rock" to 0, "paper" to 0, "scissors" to 0, "well" to 0) }

    /** The running match of a user, if any; the page's perspective (`me`), not a key of the State. */
    fun myMatch(user: String?): MatchView? = user?.let { u -> matches.firstOrNull { m -> m.finished != true && m.has(u) } }

    override fun reduce(event: Event) {
        super.reduce(event)
        when (event) {
            is Match -> event.match?.let { m -> record(m); queue.remove(m.player1); queue.remove(m.player2) }
                ?: event.player?.let { queue.add(it) }
            is Play -> event.player?.let { p -> myMatch(p)?.let { m ->
                m.played.add(Player(Json.MutableObject("id" to p)))
                if (p == m.player1) m.move1 = event.move else m.move2 = event.move   // null where the frame did not carry it
            } }
            is Round -> { record(event.match); stats!!.count(event.round) }
            is Cancel -> when (event.intent) {
                "match" -> event.user?.let { queue.remove(it) }
                "play" -> event.user?.let { u -> myMatch(u)?.let { m ->
                    m.played.remove(u)
                    if (u == m.player1) m.move1 = null else m.move2 = null
                } }
            }
            is Leave -> event.user?.id?.let { queue.remove(it) }
            else -> {}
        }
    }

    private fun record(m: MatchView) {
        if (m.finished == true) { matches.remove(m.id); stats!!.ended() } else matches.add(m)
    }
}
