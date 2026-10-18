package com.republicate.kroom.examples.chifoumi

import com.republicate.kroom.client.Client
import com.republicate.kroom.client.kroom
import com.republicate.kroom.client.say
import com.republicate.kroom.event.Cancel
import com.republicate.kroom.event.Join
import com.republicate.kroom.event.Leave
import com.republicate.kroom.examples.chifoumi.game.LobbyLogic
import com.republicate.kroom.examples.chifoumi.game.LobbyState
import com.republicate.kroom.examples.chifoumi.game.Match
import com.republicate.kroom.examples.chifoumi.game.MatchView
import com.republicate.kroom.examples.chifoumi.game.Play
import com.republicate.kroom.examples.chifoumi.game.Round
import com.republicate.kson.Json
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import kotlin.js.Date

/**
 * The browser's side of the arena, both pages: the lobby's fold is [LobbyState], the same class
 * the server runs; a page is bound to it by `data-k-*`. What stays in code is the perspective
 * (`match`, the viewer's; `game`, the game page's view of it) and the feed, a list of past frames
 * the snapshot has no memory of.
 */
fun main() {
    val client = Client(LobbyState(), LobbyLogic.registry, "chifoumi-lobby")
    client.derive { s, me -> s.json["match"] = s.myMatch(me)?.json }
    document.getElementById("activity-feed")?.let { feed ->
        client.on("join") { feed.tell(say("joined", mapOf("name" to (it as Join).user?.name))) }
        client.on("leave") { feed.tell(say("left", mapOf("name" to (it as Leave).user?.name))) }
        client.on("match") { e -> (e as Match).match?.let { m -> feed.tell(if (m.finished == true) ended(m) else say("started", mapOf("a" to m.name1, "b" to m.name2))) } }
        client.on("round") { e -> (e as Round).match.let { m -> if (m.finished == true) feed.tell(ended(m)) } }
        client.on("chat") { document.getElementById("chat-log")?.let { it.scrollTop = it.scrollHeight.toDouble() } }
    }
    if (document.getElementById("game-area") != null) GamePage(client)
    kroom.attach(client)
    window.asDynamic().kroom = kroom   // the page's handle (`kroom.connect()` once logged in)
}

private val EMOJI = mapOf("rock" to "🪨", "paper" to "📄", "scissors" to "✂️", "well" to "🕳️")

/**
 * The game page's perspective: `game`, derived from the viewer's match and from what the page
 * alone remembers — the match once the marking dropped it, the move this tab played (the frame's
 * `mine` field, lost on a reconnect), the round just resolved (on screen for two seconds), what
 * the opponent last did. Its texts come from the page's `#messages`, so l10n translates them.
 * Phases: `choose`, `waiting` (the viewer has played), `reveal`, `over`.
 */
private class GamePage(private val client: Client<LobbyState>) {
    private var last: MatchView? = null
    private var mine: String? = null
    private var reveal: Json.Object? = null
    private var note: String? = null
    private var timer: Int? = null

    init {
        client.derive { s, me -> s.json["game"] = game(s, me) }
        client.on("match") { e -> (e as Match).match?.let { m -> if (m.finished == true && ours(m)) last = m } }
        client.on("play") { e -> (e as Play).let { if (it.player == client.me) mine = it.json.getString("move")?.lowercase() else note = null } }
        client.on("cancel") { e -> (e as Cancel).let { if (it.intent == "play") { if (it.user == client.me) mine = null else if (it.user?.let { u -> client.state.myMatch(client.me)?.has(u) } == true) note = "took-back" } } }
        client.on("round") { e -> (e as Round).let { if (ours(it.match)) { if (it.match.finished == true) last = it.match; show(it) } } }
    }

    private fun ours(m: MatchView) = client.me?.let { m.has(it) } == true

    /** The round, from the viewer's side, kept on screen for a while; then the match as it stands. */
    private fun show(r: Round) {
        mine = null; note = null
        val me = client.me
        val first = r.match.player1 == me
        val round = r.round
        val winner = round.getString("winner")
        val outcome = if (winner == null) "draw" else if (winner == me) "win" else "lose"
        val key = when (round.getInt("result") ?: 0) {
            0 -> "draw"
            1 -> "${round.getString("move1")}-${round.getString("move2")}"
            else -> "${round.getString("move2")}-${round.getString("move1")}"
        }
        reveal = Json.MutableObject(
            "yours" to EMOJI[round.getString(if (first) "move1" else "move2")],
            "theirs" to EMOJI[round.getString(if (first) "move2" else "move1")],
            "outcome" to outcome,
            "message" to say("round-$outcome"),
            "verdict" to (document.querySelector("#verdicts [data-verdict=\"$key\"]")?.textContent ?: ""))
        timer?.let { window.clearTimeout(it) }
        timer = window.setTimeout({ reveal = null; timer = null; client.refresh() }, 2000)
    }

    private fun game(s: LobbyState, me: String?): Json.Object? {
        val live = s.myMatch(me)
        if (live != null) last = null   // a new match: the old one's result leaves the screen
        val m = live ?: last ?: return null
        val first = m.player1 == me
        val them = if (first) m.name2 else m.name1
        val played = me != null && me in m.played
        val theirs = m.played.any { it != me }
        val phase = when { reveal != null -> "reveal"; m.finished == true -> "over"; played -> "waiting"; else -> "choose" }
        val won = m.winner == me
        return Json.MutableObject(
            "name1" to (if (first) say("you") else m.name1), "name2" to (if (first) m.name2 else say("you")),
            "score1" to m.score1, "score2" to m.score2, "round" to m.round, "target" to m.target,
            "phase" to phase,
            "message" to when (phase) {
                "waiting" -> say("waiting")
                "choose" -> if (theirs) say("opponent-played", mapOf("name" to them)) else note?.let { say(it, mapOf("name" to them)) } ?: say("choose")
                else -> ""
            },
            "mine" to mine?.let { EMOJI[it] },
            "reveal" to reveal,
            "won" to won,
            "title" to (if (won) "${say("victory")} 🎉" else "${say("defeat")} 😢"),
            "result" to say(if (won) "defeated" else "wins", mapOf("name" to them)))
    }
}

private fun ended(m: MatchView): String {
    val first = m.winner == m.player1
    return say("beat", mapOf(
        "winner" to (if (first) m.name1 else m.name2), "loser" to (if (first) m.name2 else m.name1),
        "score" to (if (first) "${m.score1}-${m.score2}" else "${m.score2}-${m.score1}")))
}

/** The feed: newest first, ten kept, the placeholder gone with the first line. */
private fun Element.tell(text: String) {
    querySelector(".empty")?.remove()
    val li = document.createElement("li") as HTMLElement
    li.textContent = text
    val time = document.createElement("span")
    time.className = "time"
    time.textContent = Date().toLocaleTimeString()
    li.append(time)
    prepend(li)
    while (children.length > 10) lastElementChild?.remove()
}
