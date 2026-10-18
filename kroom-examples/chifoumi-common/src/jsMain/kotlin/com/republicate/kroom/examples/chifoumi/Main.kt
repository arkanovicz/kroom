package com.republicate.kroom.examples.chifoumi

import com.republicate.kroom.client.Client
import com.republicate.kroom.client.kroom
import com.republicate.kroom.client.say
import com.republicate.kroom.event.Join
import com.republicate.kroom.event.Leave
import com.republicate.kroom.examples.chifoumi.game.LobbyLogic
import com.republicate.kroom.examples.chifoumi.game.LobbyState
import com.republicate.kroom.examples.chifoumi.game.Match
import com.republicate.kroom.examples.chifoumi.game.MatchView
import com.republicate.kroom.examples.chifoumi.game.Round
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import kotlin.js.Date

/**
 * The browser's side of the arena: the lobby's fold is [LobbyState], the same class the server
 * runs; the page is bound to it by `data-k-*`. What stays in code is the perspective (`match`,
 * the viewer's) and the feed, a list of past frames the snapshot has no memory of.
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
    kroom.attach(client)
    window.asDynamic().kroom = kroom   // the page's handle (`kroom.connect()` once logged in)
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
