package com.republicate.kroom.examples.chifoumi

import com.republicate.kroom.client.Room
import com.republicate.kroom.event.Cancel
import com.republicate.kroom.event.Event
import com.republicate.kroom.event.Join
import com.republicate.kroom.event.JsonView
import com.republicate.kroom.event.Leave
import com.republicate.kroom.event.State
import com.republicate.kroom.examples.chifoumi.game.LobbyState
import com.republicate.kroom.examples.chifoumi.game.Match
import com.republicate.kroom.examples.chifoumi.game.MatchView
import com.republicate.kroom.examples.chifoumi.game.Play
import com.republicate.kroom.examples.chifoumi.game.Round
import com.republicate.kson.Json

/** A sentence of the page's, `{k}` filled: the browser's are held by the document, so l10n translates them. */
typealias Say = (key: String, vars: Map<String, Any?>) -> String

/**
 * The lobby's page memory: the feed, past frames the snapshot has no memory of (newest first, ten kept), and the
 * viewer's perspective on the marking. Its memory is fields, like any State's: a merge sees what went through them.
 */
class LobbyPage(private val room: Room<LobbyState>, private val say: Say, private val now: () -> String) : State() {
    class Line(json: Json.MutableObject = Json.MutableObject()) : JsonView(json) {
        var text by string()
        var time by string()
    }

    private val lines by log(::Line, max = 10)

    /** The feed, newest first. */
    val feed: List<Line> get() = lines.asReversed()

    val name: String? get() = room.me?.name

    /** The viewer waits for an opponent. */
    val searching: Boolean get() = room.me?.id?.let { it in room.state.queue } == true

    /** The viewer's running match, if any: the page follows it to the game. */
    val match: MatchView? get() = room.state.myMatch(room.me?.id)

    override fun reduce(event: Event) {
        when (event) {
            is Join -> tell(say("joined", mapOf("name" to event.user?.name)))
            is Leave -> tell(say("left", mapOf("name" to event.user?.name)))
            is Match -> event.match?.let { m -> tell(if (m.finished == true) ended(m, say) else say("started", mapOf("a" to m.name1, "b" to m.name2))) }
            is Round -> if (event.match.finished == true) tell(ended(event.match, say))
            else -> {}
        }
    }

    private fun tell(text: String) { lines += Line().also { it.text = text; it.time = now() } }
}

/**
 * The game page's page memory: the viewer's match, kept once the marking dropped it, the round just resolved (on
 * screen for two seconds), what the opponent last did. Phases: `choose`, `waiting` (the viewer has played), `reveal`, `over`.
 */
class GamePage(
    private val room: Room<LobbyState>,
    private val say: Say,
    /** Why a move wins, by `winner-loser` (or `draw`). */
    private val verdict: (String) -> String,
    /** Run after a delay; answers how to call it off. */
    private val later: (ms: Int, () -> Unit) -> () -> Unit,
) : State() {
    class Reveal(json: Json.MutableObject = Json.MutableObject()) : JsonView(json) {
        var yours by string()
        var theirs by string()
        var outcome by string()
        var message by string()
        var verdict by string()
    }

    private var last by obj(::MatchView)
    var reveal by obj(::Reveal)
        private set
    private var note by string()
    private var timer: (() -> Unit)? = null   // not memory: how to call the reveal off

    private val me: String? get() = room.me?.id
    private val live: MatchView? get() = room.state.myMatch(me)
    private val match: MatchView? get() = live ?: last

    override fun reduce(event: Event) {
        when (event) {
            is Match -> event.match?.let { m -> if (ours(m)) { last = if (m.finished == true) m else null } }
            is Play -> if (event.player != me) note = null
            is Cancel -> if (event.intent == "play" && event.user != me && event.user?.let { live?.has(it) } == true) note = "took-back"
            is Round -> if (ours(event.match)) { last = if (event.match.finished == true) event.match else null; show(event) }
            else -> {}
        }
    }

    private fun ours(m: MatchView) = me?.let { m.has(it) } == true

    /** The round from the viewer's side, kept on screen for a while; then the match as it stands. */
    private fun show(r: Round) {
        note = null
        val first = r.match.player1 == me
        val round = r.round
        val winner = round.getString("winner")
        val outcome = if (winner == null) "draw" else if (winner == me) "win" else "lose"
        val key = when (round.getInt("result") ?: 0) {
            0 -> "draw"
            1 -> "${round.getString("move1")}-${round.getString("move2")}"
            else -> "${round.getString("move2")}-${round.getString("move1")}"
        }
        reveal = Reveal().also {
            it.yours = EMOJI[round.getString(if (first) "move1" else "move2")]
            it.theirs = EMOJI[round.getString(if (first) "move2" else "move1")]
            it.outcome = outcome
            it.message = say("round-$outcome", emptyMap())
            it.verdict = verdict(key)
        }
        timer?.invoke()
        timer = later(2000) { reveal = null; timer = null; room.merge() }
    }

    /** A match to show: the page stays on the game. */
    val present: Boolean get() = match != null

    private val first: Boolean get() = match?.player1 == me

    /** The viewer's move of the round in progress: the marking's, its own to see. */
    val move: String? get() = live?.let { if (first) it.move1 else it.move2 }?.name?.lowercase()?.let { EMOJI[it] }
    private val them: String? get() = match?.let { if (first) it.name2 else it.name1 }

    val name1: String? get() = match?.let { if (first) say("you", emptyMap()) else it.name1 }
    val name2: String? get() = match?.let { if (first) it.name2 else say("you", emptyMap()) }
    val score1: Int? get() = match?.score1
    val score2: Int? get() = match?.score2
    val round: Int? get() = match?.round
    val target: Int? get() = match?.target

    val phase: String? get() {
        val m = match ?: return null
        return when { reveal != null -> "reveal"; m.finished == true -> "over"; me?.let { it in m.played } == true -> "waiting"; else -> "choose" }
    }

    val message: String get() = when (phase) {
        "waiting" -> say("waiting", emptyMap())
        "choose" -> if (match?.played?.any { it.id != me } == true) say("opponent-played", mapOf("name" to them))
                    else note?.let { say(it, mapOf("name" to them)) } ?: say("choose", emptyMap())
        else -> ""
    }

    val won: Boolean get() = match?.winner == me
    val title: String get() = if (won) "${say("victory", emptyMap())} 🎉" else "${say("defeat", emptyMap())} 😢"
    val result: String get() = say(if (won) "defeated" else "wins", mapOf("name" to them))

    /** The viewer waits for a new opponent, from the result screen. */
    val searching: Boolean get() = me?.let { it in room.state.queue } == true
}

private val EMOJI = mapOf("rock" to "🪨", "paper" to "📄", "scissors" to "✂️", "well" to "🕳️")

private fun ended(m: MatchView, say: Say): String {
    val first = m.winner == m.player1
    return say("beat", mapOf(
        "winner" to (if (first) m.name1 else m.name2), "loser" to (if (first) m.name2 else m.name1),
        "score" to (if (first) "${m.score1}-${m.score2}" else "${m.score2}-${m.score1}")))
}
