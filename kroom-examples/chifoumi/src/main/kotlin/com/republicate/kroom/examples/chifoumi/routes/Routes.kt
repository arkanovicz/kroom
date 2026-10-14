package com.republicate.kroom.examples.chifoumi.routes

import com.republicate.kroom.examples.chifoumi.game.MatchStats
import com.republicate.kroom.examples.chifoumi.sse.ChifoumiLobby
import com.republicate.kroom.server.Actor
import com.republicate.kroom.server.Users
import com.republicate.kroom.server.intents
import com.republicate.kroom.server.tabId
import com.republicate.kroom.webapp.core.respondError
import com.republicate.kroom.webapp.core.respondJson
import com.republicate.kroom.webapp.core.respondSuccess
import com.republicate.kroom.webapp.l10n.language
import com.republicate.kroom.webapp.l10n.respondVelocityTranslated
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.sse.*
import kotlinx.coroutines.flow.consumeAsFlow
import kotlin.time.Duration.Companion.seconds

/**
 * Simple session for anonymous players.
 */
@kotlinx.serialization.Serializable
data class PlayerSession(val identifier: String, val playerName: String)

/**
 * Configure all routes for Chifoumi.
 */
fun Route.chifoumiRoutes() {
    // SSE endpoint for real-time updates
    sse("/events") {
        heartbeat { period = 15.seconds }
        val session = call.sessions.get<PlayerSession>()
        if (session == null) {
            call.respond(HttpStatusCode.Unauthorized, "Not logged in")
            return@sse
        }

        // identity = the session; the tab id keeps a reconnecting tab the same connection
        val user = Users.getOrCreate(session.identifier, session.playerName)
        val actor = Actor(Actor.connectionId(user, call.tabId), user, session.playerName)
        val channel = ChifoumiLobby.join(actor)

        try {
            channel.consumeAsFlow().collect { event ->
                send(event)
            }
        } finally {
            ChifoumiLobby.leave(actor)
        }
    }

    // API routes
    route("/api") {
        // Join game (create/get session)
        post("/join") {
            val body = call.receiveText()
            val name = com.republicate.kson.Json.parse(body)?.asObject()?.getString("name")
                ?: return@post respondError("Name required")

            // Sanitize name
            val cleanName = name.trim().take(20).replace(Regex("[^a-zA-Z0-9_-]"), "")
            if (cleanName.length < 2) {
                return@post respondError("Name must be at least 2 characters")
            }

            val session = PlayerSession(
                identifier = java.util.UUID.randomUUID().toString().take(8),
                playerName = cleanName
            )
            call.sessions.set(session)

            respondJson {
                set("success", true)
                set("id", session.identifier)
                set("name", session.playerName)
            }
        }

        // Get current session
        get("/session") {
            val session = call.sessions.get<PlayerSession>()
            if (session != null) {
                respondJson {
                    set("id", session.identifier)
                    set("name", session.playerName)
                }
            } else {
                respondJson {
                    set("logged_in", false)
                }
            }
        }

        // Leave (logout)
        post("/leave") {
            call.sessions.clear<PlayerSession>()
            respondSuccess("Goodbye!")
        }

        // Every move of a player: POST /api/rooms/chifoumi-lobby/act {type, ...}
        intents { call ->
            call.sessions.get<PlayerSession>()?.let { Users.getOrCreate(it.identifier, it.playerName) }
        }

        // Get current match status
        get("/match") {
            val session = call.sessions.get<PlayerSession>()
                ?: return@get respondError("Not logged in", HttpStatusCode.Unauthorized)

            val match = ChifoumiLobby.getMatch(session.playerName)
            if (match != null) {
                respondJson { set("match", match.toJson()) }
            } else {
                respondJson { set("match", null) }
            }
        }

        // Get stats
        get("/stats") {
            respondJson { set("stats", MatchStats.toJson()) }
        }

        // Get lobby info
        get("/lobby") {
            respondJson {
                set("online", ChifoumiLobby.getActors().size)
                set("queue", ChifoumiLobby.getQueueSize())
                set("matches", ChifoumiLobby.getActiveMatchCount())
            }
        }
    }
}

/**
 * Page routes (Velocity templates).
 */
fun Route.chifoumiPages() {
    get("/index.html") {
        call.respondVelocityTranslated("templates/index.html")
    }
    get("/game.html") {
        call.respondVelocityTranslated("templates/game.html")
    }
    // Redirect root to index
    get { call.respondRedirect("/${call.language}/index.html") }
    get("/") { call.respondRedirect("/${call.language}/index.html") }
}
