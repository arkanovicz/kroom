package com.republicate.kroom.server

import com.republicate.kroom.event.Stage
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import com.republicate.kson.Json

/** The tab id: a query parameter on the event stream, a header (`X-Kroom-Tab`) on an intent. */
val ApplicationCall.tabId: String?
    get() = request.queryParameters["tabId"] ?: request.header("X-Kroom-Tab")

private suspend fun ApplicationCall.json(status: HttpStatusCode, body: String) = respondText(body, ContentType.Application.Json, status)
private fun refusal(reason: String) = """{"_":{"stage":"REJECTED","reason":"$reason","args":{}}}"""

/**
 * The door, `POST /rooms/{room}/act` with `{type, …}`: the event of that name over that JSON goes
 * to the room; the answer is the event with its stage, `200` applied, `400` rejected — an honest
 * status the browser does not read (the event comes back down the stream). [principal] names the
 * acting user from the call (its session); null refuses.
 */
fun Route.intents(principal: suspend (ApplicationCall) -> User?) {
    post("/rooms/{room}/act") {
        val user = principal(call) ?: return@post call.json(HttpStatusCode.Unauthorized, refusal("unauthenticated"))
        val room = Lobby.get<Any>(call.parameters["room"]!!) ?: return@post call.json(HttpStatusCode.NotFound, refusal("no_room"))
        val json = Json.parse(call.receiveText()) as? Json.MutableObject ?: return@post call.json(HttpStatusCode.BadRequest, refusal("not_an_object"))
        val name = json.getString("type") ?: return@post call.json(HttpStatusCode.BadRequest, refusal("no_type"))
        json.remove("type")
        val event = room.act(user, call.tabId, name, json)
        call.json(if (event.stage == Stage.APPLIED) HttpStatusCode.OK else HttpStatusCode.BadRequest, event.json.toString())
    }
}

/** The catalog as data, `GET /rooms/{room}/events`: what a browser without Kotlin validates against. */
fun Route.events() {
    get("/rooms/{room}/events") {
        val room = Lobby.get<Any>(call.parameters["room"]!!) ?: return@get call.json(HttpStatusCode.NotFound, refusal("no_room"))
        call.json(HttpStatusCode.OK, room.catalog.describe().toString())
    }
}

/**
 * Scroll-back, `GET /rooms/{room}/history?before=<seq>&n=<count>` → `{messages: [...]}`, oldest
 * first; no `before` means from the latest. [principal] names the reading user; null refuses.
 */
fun Route.chatHistory(principal: suspend (ApplicationCall) -> User?) {
    get("/rooms/{room}/history") {
        principal(call) ?: return@get call.json(HttpStatusCode.Unauthorized, refusal("unauthenticated"))
        val room = Lobby.get<Any>(call.parameters["room"]!!) as? ChatRoom ?: return@get call.json(HttpStatusCode.NotFound, refusal("no_room"))
        val before = call.request.queryParameters["before"]?.toLongOrNull() ?: Long.MAX_VALUE
        val n = (call.request.queryParameters["n"]?.toIntOrNull() ?: 50).coerceIn(1, 500)
        call.json(HttpStatusCode.OK, Json.Object("messages" to Json.Array(room.before(before, n).map { it.toJson() })).toString())
    }
}
