package com.republicate.kroom.examples.chifoumi

import com.republicate.kroom.client.Room
import com.republicate.kroom.client.attach
import com.republicate.kroom.client.kroom
import com.republicate.kroom.client.say
import com.republicate.kroom.examples.chifoumi.game.LobbyEvents
import com.republicate.kroom.examples.chifoumi.game.LobbyState
import kotlinx.browser.document
import kotlinx.browser.window
import org.apache.velocity.engine.Config
import org.apache.velocity.engine.VelocityEngine
import org.apache.velocity.engine.resource.StockResourceLoader
import velocity.templates.VelocityTemplates
import kotlin.js.Date

/**
 * The browser's side of the arena, both pages: the lobby's fold is [LobbyState], the same class the server runs;
 * the room's parts of the page are regions, compiled pages merged into the document after every frame, in the
 * document's language. What stays in code is the page memory ([LobbyPage], [GamePage]) and the reactions.
 */
fun main() {
    val room = Room("chifoumi-lobby", LobbyState(), LobbyEvents, engine)
    val lang = document.documentElement?.getAttribute("lang") ?: "en"
    fun region(id: String, template: String) = document.getElementById(id)?.let { room.attach("$lang/$template", it) }
    val say: Say = { key, vars -> say(key, vars) }

    if (document.getElementById("lobby-section") != null) {
        val page = LobbyPage(room, say) { Date().toLocaleTimeString() }
        room.page = page
        region("online-num", "online.html")
        region("lobby-section", "lobby.html")
        room.on(LobbyEvents.chat) { document.getElementById("chat-log")?.let { it.scrollTop = it.scrollHeight.toDouble() } }
        room.react { _, _ -> if (page.match != null) window.location.href = "/$lang/game.html" }   // the viewer's match is on (a reconnect lands there too)
    }
    if (document.getElementById("game-area") != null) {
        val page = GamePage(room, say,
            verdict = { key -> document.querySelector("#verdicts [data-kroom-verdict=\"$key\"]")?.textContent ?: "" },
            later = { ms, fn -> val t = window.setTimeout(fn, ms); { window.clearTimeout(t) } })
        room.page = page
        region("match-nav", "round.html")
        region("game-area", "match.html")
        room.react { _, _ -> if (!page.present) window.location.href = "/$lang/index.html" }   // no match of the viewer's: back to the lobby
    }
    kroom.attach(room)
    window.asDynamic().kroom = kroom   // the page's handle (`kroom.connect()` once logged in)
}

/** The pages, compiled at build: an engine without an interpreter, so no parser ships. */
private val engine = VelocityEngine(null, Config(informalNavigation = true)).apply { addResourceLoader("stock", StockResourceLoader(VelocityTemplates::resolve)) }

