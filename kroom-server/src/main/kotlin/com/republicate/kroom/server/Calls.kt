package com.republicate.kroom.server

import com.republicate.kroom.event.Event
import com.republicate.kroom.event.Stage
import io.ktor.server.application.*
import io.ktor.server.http.HttpRequestLifecycle
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.http.*
import com.republicate.kson.Json
import io.ktor.utils.io.ClosedByteChannelException
import io.ktor.utils.io.ConnectionClosedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.consumeAsFlow
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

/** The tab id: a query parameter on the event stream, a header (`X-Kroom-Tab`) on an intent. */
val ApplicationCall.tabId: String?
    get() = request.queryParameters["tabId"] ?: request.header("X-Kroom-Tab")

private val logger = LoggerFactory.getLogger("kroom.room")

private suspend fun ApplicationCall.json(status: HttpStatusCode, body: String) = respondText(body, ContentType.Application.Json, status)
private fun refusal(reason: String, args: Json.Object = Json.Object()) = Json.Object("reason" to reason, "args" to args).toString()

/**
 * Everything a room serves, read from its events: the stream (`GET /rooms/{id}/stream`, the viewer's snapshot then
 * every frame, ktor's heartbeat every 15 s), one `POST /rooms/{id}/{name}` per intent, the catalog (`GET
 * /rooms/{id}/events`), and for a chat the scroll-back (`GET /rooms/{id}/history`). [principal] names the user from
 * the call (its session), null for anonymous; the room's [Room.role] says what that user is there.
 */
fun Route.room(room: Room<*>, principal: suspend (ApplicationCall) -> User?) = serve("/rooms/${room.id}", { room }, principal)

/**
 * The same routes for every room of a kind, `/rooms/{room}/…`: the room is the [Rooms]'s, else [build]s (null: no
 * such room, 404); one built here closes once idle (see [Rooms.getOrCreate]).
 */
fun Route.rooms(build: suspend (id: String) -> Room<*>?, principal: suspend (ApplicationCall) -> User?) =
    serve("/rooms/{room}", { call -> Rooms.getOrCreate(call.parameters["room"]!!, build) }, principal)

private fun Route.serve(base: String, resolve: suspend (ApplicationCall) -> Room<*>?, principal: suspend (ApplicationCall) -> User?) {
    if (application.pluginOrNull(SSE) == null) application.install(SSE)
    route("$base/stream") {
        // a stream closed by the client is cancelled at once (Netty, CIO), not at the next heartbeat; the stream alone:
        // an app's other calls finish whether or not the client waits
        install(HttpRequestLifecycle) { cancelCallOnClose = true }
        get {
            val user = principal(call)
            // admitted before the 200: a refusal is a status, not a stream that ends
            val (room, actor) = call.inRoom(resolve) { it to it.connect(user, call.tabId) } ?: return@get call.noRoom()
            if (actor == null) return@get call.refuse(user)
            try {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.response.header("X-Accel-Buffering", "no")
                // the body is written after respond returns: the stream's own end — a write failed, the call cancelled — is the leave
                call.respond(SSEServerContent(call) {
                    heartbeat { period = 15.seconds }
                    // a tab gone, seen at a write or as the call cancelled: one line, no trace
                    fun gone() = logger.info("Stream of '{}' closed by the client in room '{}'", actor.connectionId, room.id)
                    try { actor.channel?.consumeAsFlow()?.collect { send(it) } }
                    catch (e: ClosedByteChannelException) { gone() }
                    catch (e: CancellationException) { if (generateSequence<Throwable>(e) { it.cause }.any { it is ConnectionClosedException }) gone(); throw e }
                    finally { room.leave(actor) }
                })
            } catch (e: Throwable) { room.leave(actor); throw e }   // gone before the stream started
        }
    }
    post("$base/{name}") {
        val user = principal(call) ?: return@post call.json(HttpStatusCode.Unauthorized, refusal("unauthenticated"))
        val name = call.parameters["name"]!!
        val body = call.receiveText()
        call.inRoom(resolve) { room ->
            if (name !in room.events.names) return@inRoom call.json(HttpStatusCode.NotFound, refusal("unknown"))
            val json = Json.parse(body) as? Json.MutableObject ?: return@inRoom call.json(HttpStatusCode.BadRequest, refusal("not_an_object"))
            call.verdict(room.act(user, call.tabId, name, json))
        } ?: call.noRoom()
    }
    get("$base/events") {
        val user = principal(call)
        call.inRoom(resolve) { room ->
            if (room.admit(user) == null) call.refuse(user) else call.json(HttpStatusCode.OK, room.describe().toString())
        } ?: call.noRoom()
    }
    get("$base/history") {
        val user = principal(call)
        call.inRoom(resolve) { room ->
            if (room !is ChatRoom<*, *>) call.json(HttpStatusCode.NotFound, refusal("no_history"))
            else if (room.admit(user) == null) call.refuse(user)
            else call.history(room, user)
        } ?: call.noRoom()
    }
}

/**
 * [block] on the room [resolve] names; null: no such room. A room built on demand may close under the call
 * ([ClosedSendChannelException]): the next resolve builds it again.
 */
private suspend fun <T : Any> ApplicationCall.inRoom(resolve: suspend (ApplicationCall) -> Room<*>?, block: suspend (Room<*>) -> T): T? {
    repeat(2) {
        val room = resolve(this) ?: return null
        try { return block(room) } catch (e: ClosedSendChannelException) { logger.debug("Room '{}' closed under a call", room.id) }
    }
    return null
}

private suspend fun ApplicationCall.noRoom() = json(HttpStatusCode.NotFound, refusal("no_room"))

private suspend fun ApplicationCall.refuse(user: User?) =
    if (user == null) json(HttpStatusCode.Unauthorized, refusal("unauthenticated")) else json(HttpStatusCode.Forbidden, refusal("forbidden"))

/**
 * One intent's reply is its verdict and nothing else: `200` `{}` applied; `403` `{reason}` for whom the room refuses
 * (`spectator`, `forbidden`), `400` `{reason, args}` otherwise. The effects come down the stream, to the emitter like
 * to everyone. A room closed meanwhile is resolved again ([inRoom]).
 */
private suspend fun ApplicationCall.verdict(event: Event) {
    val reason = event.meta.reason
    when {
        // a store may have moved it on to PERSISTED already: the verdict is rejected or not
        event.stage != Stage.REJECTED -> json(HttpStatusCode.OK, "{}")
        reason == "closed" -> throw ClosedSendChannelException("closed")
        reason == "spectator" || reason == "forbidden" -> json(HttpStatusCode.Forbidden, refusal(reason, event.meta.args ?: Json.Object()))
        else -> json(HttpStatusCode.BadRequest, refusal(reason ?: "rejected", event.meta.args ?: Json.Object()))
    }
}

/**
 * The history, `?before=<seq>` (scroll-back; none: from the latest), `?after=<seq>` (reading on from a line jumped
 * to), both (strictly between), `&n=<count>` → `{messages: [...]}`, oldest first, each line as [user]'s snapshot shows
 * it. Any other query parameter is the store's ([ChatHistory.between]).
 */
private suspend fun ApplicationCall.history(room: ChatRoom<*, *>, user: User?) {
    val after = request.queryParameters["after"]?.toLongOrNull()
    val before = request.queryParameters["before"]?.toLongOrNull()
    val n = (request.queryParameters["n"]?.toIntOrNull() ?: 50).coerceIn(1, 500)
    val params = request.queryParameters.entries().filter { it.key !in HISTORY_PARAMS }.associate { it.key to it.value.first() }
    json(HttpStatusCode.OK, Json.Object("messages" to Json.Array(room.between(after, before, n, params).map { it.view(user?.userId) })).toString())
}

private val HISTORY_PARAMS = setOf("after", "before", "n")
