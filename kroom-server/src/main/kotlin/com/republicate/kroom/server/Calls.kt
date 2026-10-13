package com.republicate.kroom.server

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import com.republicate.kson.Json

/**
 * What a connecting or acting browser tells about itself.
 * `EventSource` sends `Last-Event-ID` on the reconnects it performs itself; sse.js passes
 * `lastEventId` as a query parameter on the fresh connection it opens after a `CLOSED`.
 */
val ApplicationCall.lastEventId: String?
    get() = request.header("Last-Event-ID") ?: request.queryParameters["lastEventId"]

/** The tab id: a query parameter on the event stream, a header (`X-Kroom-Tab`) on an intent. */
val ApplicationCall.tabId: String?
    get() = request.queryParameters["tabId"] ?: request.header("X-Kroom-Tab")

/**
 * The intent door, `POST /rooms/{room}/act` with `{type, …}`: parse, act, answer an honest status
 * the browser does not read (its verdict comes down the stream as `accepted` / `rejected`).
 * [principal] names the acting user from the call (its session); null refuses.
 */
fun Route.intents(principal: suspend (ApplicationCall) -> User?) {
    post("/rooms/{room}/act") {
        val user = principal(call)
            ?: return@post call.respondText("""{"error":"not logged in"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
        val room = Lobby.get<Any>(call.parameters["room"]!!)
            ?: return@post call.respondText("""{"error":"no such room"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
        val intent = Json.parse(call.receiveText())?.asObject()
            ?: return@post call.respondText("""{"error":"an intent is a JSON object"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
        when (val verdict = room.act(user, call.tabId, intent)) {
            is ActionResult.Success -> call.respondText("{}", ContentType.Application.Json)
            is ActionResult.Error -> call.respondText(Json.Object("error" to verdict.message).toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
        }
    }
}

/**
 * Scroll-back, `GET /rooms/{room}/history?before=<seq>&n=<count>` → `{messages: [...]}`, oldest
 * first; no `before` means from the latest. [principal] names the reading user; null refuses.
 */
fun Route.chatHistory(principal: suspend (ApplicationCall) -> User?) {
    get("/rooms/{room}/history") {
        principal(call)
            ?: return@get call.respondText("""{"error":"not logged in"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
        val room = Lobby.get<Any>(call.parameters["room"]!!) as? ChatRoom
            ?: return@get call.respondText("""{"error":"no such chat room"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
        val before = call.request.queryParameters["before"]?.toLongOrNull() ?: Long.MAX_VALUE
        val n = (call.request.queryParameters["n"]?.toIntOrNull() ?: 50).coerceIn(1, 500)
        val messages = room.before(before, n)
        call.respondText(Json.Object("messages" to Json.Array(messages.map { it.toJson() })).toString(), ContentType.Application.Json)
    }
}
