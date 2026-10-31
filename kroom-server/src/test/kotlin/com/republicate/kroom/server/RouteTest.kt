package com.republicate.kroom.server

import com.republicate.kson.Json
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

/** Pins what a room serves, read from its events: the stream, one route per intent, the catalog, a chat's scroll-back. */
class RouteTest {
    private var n = 0
    private val principal: suspend (io.ktor.server.application.ApplicationCall) -> User? =
        { call -> call.request.headers["X-User"]?.let { Users.getOrCreate(it, it) } }

    @BeforeTest fun clean() = Users.clear()

    @Test fun `one route per intent, the verdict on the reply`() = testApplication {
        val room = LogicRoom("route-${n++}", LogicRoomTest.Counter(), LogicRoomTest.Rule)
        routing { route("/api") { room(room, principal) } }
        suspend fun post(name: String, body: String, user: String? = "alice") = client.post("/api/rooms/${room.id}/$name") {
            user?.let { header("X-User", it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        post("add", """{"by":3}""").let { assertEquals(HttpStatusCode.OK, it.status); assertEquals("{}", it.bodyAsText()) }
        assertEquals(3, room.state.n)
        post("add", """{"by":20}""").let {
            assertEquals(HttpStatusCode.BadRequest, it.status)
            assertEquals("too_big", (Json.parse(it.bodyAsText()) as Json.Object).getString("reason"))
        }
        assertEquals(HttpStatusCode.Unauthorized, post("add", """{"by":1}""", user = null).status)
        assertEquals(HttpStatusCode.NotFound, post("set", """{"n":1}""").status)   // emitted, not posted: no route
        assertEquals(HttpStatusCode.BadRequest, post("add", """{"by":1,"n":2}""").status)   // the door's shape
        val catalog = Json.parse(client.get("/api/rooms/${room.id}/events").bodyAsText()) as Json.Object
        assertEquals(listOf("join", "leave", "status", "cancel", "add", "note", "wait", "whisper", "set"),
            catalog.getArray("events")!!.map { (it as Json.Object).getString("name") })
        room.stop()
    }

    @Test fun `the stream opens with the viewer's snapshot, a chat serves its scroll-back`() = testApplication {
        val room = ChatRoom("chat-route-${n++}")
        routing { route("/api") { room(room, principal) } }
        val sse = createClient { install(io.ktor.client.plugins.sse.SSE) }
        sse.sse("/api/rooms/${room.id}/stream?tabId=t1", { header("X-User", "alice") }) {
            val first = incoming.first()
            assertEquals("state", first.event)
            assertEquals("alice", (Json.parse(first.data!!) as Json.Object).getObject("me")!!.getString("id"))
        }
        client.post("/api/rooms/${room.id}/chat") { header("X-User", "alice"); contentType(ContentType.Application.Json); setBody("""{"text":"hi"}""") }
        val history = Json.parse(client.get("/api/rooms/${room.id}/history") { header("X-User", "alice") }.bodyAsText()) as Json.Object
        assertEquals(listOf("hi"), history.getArray("messages")!!.map { (it as Json.Object).getString("text") })
        room.stop()
    }
}
