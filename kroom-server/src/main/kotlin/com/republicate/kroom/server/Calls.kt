package com.republicate.kroom.server

import com.republicate.kroom.event.Stage
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.http.*
import com.republicate.kson.Json
import kotlinx.coroutines.flow.consumeAsFlow
import kotlin.time.Duration.Companion.seconds

/** The tab id: a query parameter on the event stream, a header (`X-Kroom-Tab`) on an intent. */
val ApplicationCall.tabId: String?
    get() = request.queryParameters["tabId"] ?: request.header("X-Kroom-Tab")

private suspend fun ApplicationCall.json(status: HttpStatusCode, body: String) = respondText(body, ContentType.Application.Json, status)
private fun refusal(reason: String, args: Json.Object = Json.Object()) = Json.Object("reason" to reason, "args" to args).toString()

/**
 * Everything a room serves, read from its events: the stream (`GET /rooms/{id}/stream`, the viewer's snapshot then
 * every frame), one `POST /rooms/{id}/{name}` per intent, the catalog (`GET /rooms/{id}/events`), and for a chat the
 * scroll-back (`GET /rooms/{id}/history`). [principal] names the user from the call (its session); null refuses.
 */
fun Route.room(room: Room<*>, principal: suspend (ApplicationCall) -> User?) {
    val base = "/rooms/${room.id}"
    if (application.pluginOrNull(SSE) == null) application.install(SSE)
    sse("$base/stream") {
        heartbeat { period = 15.seconds }
        val user = principal(call) ?: return@sse call.respond(HttpStatusCode.Unauthorized, refusal("unauthenticated"))
        // the tab id keeps a reconnecting tab the same connection
        val actor = Actor(Actor.connectionId(user, call.tabId), user)
        val channel = room.join(actor)
        try { channel.consumeAsFlow().collect { send(it) } } finally { room.leave(actor) }
    }
    room.events.names.forEach { name -> post("$base/$name") { call.intent(room, name, principal) } }
    get("$base/events") { call.json(HttpStatusCode.OK, room.describe().toString()) }
    if (room is ChatRoom<*>) get("$base/history") { call.history(room, principal) }
}

/**
 * Intents to any room the [Lobby] knows, `POST /rooms/{room}/{name}` with the event's payload: what [room] mounts for
 * one room, for all of them.
 */
fun Route.intents(principal: suspend (ApplicationCall) -> User?) {
    post("/rooms/{room}/{name}") {
        val room = Lobby.get<Any>(call.parameters["room"]!!) ?: return@post call.json(HttpStatusCode.NotFound, refusal("no_room"))
        call.intent(room, call.parameters["name"]!!, principal)
    }
}

/** The room's events as data, `GET /rooms/{room}/events`: the state's fields and the alphabet, what a browser without Kotlin reads. */
fun Route.events() {
    get("/rooms/{room}/events") {
        val room = Lobby.get<Any>(call.parameters["room"]!!) ?: return@get call.json(HttpStatusCode.NotFound, refusal("no_room"))
        call.json(HttpStatusCode.OK, room.describe().toString())
    }
}

/**
 * Scroll-back, `GET /rooms/{room}/history?before=<seq>&n=<count>` → `{messages: [...]}`, oldest
 * first; no `before` means from the latest. [principal] names the reading user; null refuses.
 */
fun Route.chatHistory(principal: suspend (ApplicationCall) -> User?) {
    get("/rooms/{room}/history") {
        val room = Lobby.get<Any>(call.parameters["room"]!!) as? ChatRoom<*> ?: return@get call.json(HttpStatusCode.NotFound, refusal("no_room"))
        call.history(room, principal)
    }
}

/**
 * One intent: the event of that name over the posted JSON goes to the room; the reply is the verdict and nothing
 * else — `200` `{}` applied, `400` `{reason, args}` rejected. The effects come down the stream, to the emitter like
 * to everyone.
 */
private suspend fun ApplicationCall.intent(room: Room<*>, name: String, principal: suspend (ApplicationCall) -> User?) {
    val user = principal(this) ?: return json(HttpStatusCode.Unauthorized, refusal("unauthenticated"))
    val json = Json.parse(receiveText()) as? Json.MutableObject ?: return json(HttpStatusCode.BadRequest, refusal("not_an_object"))
    val event = room.act(user, tabId, name, json)
    // a store may have moved it on to PERSISTED already: the verdict is rejected or not
    if (event.stage == Stage.REJECTED) json(HttpStatusCode.BadRequest, refusal(event.meta.reason ?: "rejected", event.meta.args ?: Json.Object()))
    else json(HttpStatusCode.OK, "{}")
}

private suspend fun ApplicationCall.history(room: ChatRoom<*>, principal: suspend (ApplicationCall) -> User?) {
    principal(this) ?: return json(HttpStatusCode.Unauthorized, refusal("unauthenticated"))
    val before = request.queryParameters["before"]?.toLongOrNull() ?: Long.MAX_VALUE
    val n = (request.queryParameters["n"]?.toIntOrNull() ?: 50).coerceIn(1, 500)
    json(HttpStatusCode.OK, Json.Object("messages" to Json.Array(room.before(before, n).map { it.toJson() })).toString())
}
