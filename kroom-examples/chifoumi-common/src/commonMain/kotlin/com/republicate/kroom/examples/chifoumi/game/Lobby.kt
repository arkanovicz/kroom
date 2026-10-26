package com.republicate.kroom.examples.chifoumi.game

import com.republicate.kroom.event.Cancel
import com.republicate.kroom.event.Chat
import com.republicate.kroom.event.ChatState
import com.republicate.kroom.event.Event
import com.republicate.kroom.event.Fires
import com.republicate.kroom.event.JsonView
import com.republicate.kroom.event.Leave
import com.republicate.kson.Json

/*
 * The lobby's grammar — what a generator will write this file from:
 *
 *   room chifoumi-lobby {
 *     state(history: log<Chat>, queue: keyed<Player>, matches: keyed<Match>, stats: Stats)
 *     event chat(text: String)
 *     event match pending -> match(player: User, match?: Match)
 *     event play(move: Move @mine) pending -> play(player: User) | round(match: Match, round: Round)
 *     event leave -> match
 *   }
 *
 * A name on both sides is the intent's own frame with its `out` fields; a right-hand name of its
 * own is an event nobody posts. `cancel`, `join`, `leave`, `status` are kroom's.
 */

/** A user as the lobby lists one: who waits, who has played the round. */
class Player(json: Json.MutableObject = Json.MutableObject()) : JsonView(json) {
    val id by string().required
    val name by string()
}

/** A match as the wire sees it: scores, round, winner; never a move in progress. */
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
    fun has(user: String) = user == player1 || user == player2
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

/** Wanting a match: held while nobody else does, paired with the first who does. `leave` ends a match the same way, forfeited. */
class Match(json: Json.MutableObject = Json.MutableObject()) : Event("match", pending = true, json = json) {
    var player by obj(::Player).out     // who wants one (a deposit); absent when the room ends a match
    var match by obj(::MatchView).out   // the match, when one starts or ends
}

/** A move: the author's tabs alone see which; everyone sees who played. Held until the other plays. */
class Play(json: Json.MutableObject = Json.MutableObject()) : Event("play", pending = true, fires = Fires(::Round), json = json) {
    val move by enum<Move>().required.mine
    var player by string().out
}

/** Both have played: the round, resolved, and the match as it stands. */
class Round(json: Json.MutableObject = Json.MutableObject()) : Event("round", json = json) {
    var match by obj(::MatchView).required.out
    var round by obj().required.out
}

/** The lobby's marking: the chat's tail, who waits, the matches running, the totals. */
class LobbyState(json: Json.MutableObject = Json.MutableObject()) : ChatState(json) {
    val queue by keyed(::Player)
    val matches by keyed(::MatchView)
    val stats by obj(::Stats)

    init { if (json["stats"] == null) json["stats"] = Json.MutableObject("played" to 0, "rock" to 0, "paper" to 0, "scissors" to 0, "well" to 0) }

    /** The running match of a user, if any; the page's perspective (`me`), not a key of the marking. */
    fun myMatch(user: String?): MatchView? = user?.let { u -> matches.firstOrNull { m -> m.finished != true && m.has(u) } }

    override fun reduce(event: Event) {
        super.reduce(event)
        when (event) {
            is Match -> event.match?.let { m -> record(m); queue.remove(m.player1); queue.remove(m.player2) }
                ?: event.player?.let { queue.add(it) }
            is Play -> event.player?.let { p -> myMatch(p)?.played?.add(Player(Json.MutableObject("id" to p))) }
            is Round -> { record(event.match); stats!!.count(event.round) }
            is Cancel -> when (event.intent) {
                "match" -> event.user?.let { queue.remove(it) }
                "play" -> event.user?.let { u -> myMatch(u)?.played?.remove(u) }
            }
            is Leave -> event.user?.id?.let { queue.remove(it) }
            else -> {}
        }
    }

    private fun record(m: MatchView) {
        if (m.finished == true) { matches.remove(m.id); stats!!.ended() } else matches.add(m)
    }
}
