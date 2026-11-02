package com.republicate.kroom.server

import com.republicate.kroom.event.Chat
import com.republicate.kroom.event.ChatLogic
import com.republicate.kroom.event.ChatState
import com.republicate.kson.Json
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

/** Arkad's levels: public reads for anyone and writes for the signed-in, open is the signed-in's, private its members'. */
class Club(id: String, val level: String, val members: Set<String> = emptySet()) : LogicRoom<LogicRoomTest.Counter>(id, LogicRoomTest.Counter(), LogicRoomTest.Rule) {
    override fun role(user: User?) = when (level) {
        "public" -> if (user != null) Role.ACTOR else Role.SPECTATOR
        "open" -> user?.let { Role.ACTOR }
        else -> user?.takeIf { it.userId in members }?.let { Role.ACTOR }
    }
}

/** A chat alice writes in and everyone signed-in reads. */
class Gallery(id: String) : ChatRoom<Chat, ChatState<Chat>>(id, MemoryChatHistory(), ChatState(), ChatLogic()) {
    override fun role(user: User?) = when (user?.userId) { null -> null; "alice" -> Role.ACTOR; else -> Role.SPECTATOR }
}

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
        val catalog = Json.parse(client.get("/api/rooms/${room.id}/events") { header("X-User", "alice") }.bodyAsText()) as Json.Object
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

    @Test fun `the scroll-back serves an app's lines as the reader's snapshot would`() = testApplication {
        val room = lineRoom("chat-route-${n++}")
        routing { route("/api") { room(room, principal) } }
        client.post("/api/rooms/${room.id}/chat") { header("X-User", "alice"); contentType(ContentType.Application.Json); setBody("""{"text":"hi"}""") }
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/rooms/${room.id}/chat") { header("X-User", "alice"); contentType(ContentType.Application.Json); setBody("""{"text":"x","mood":"faked"}""") }.status)
        suspend fun line(user: String) = ((Json.parse(client.get("/api/rooms/${room.id}/history") { header("X-User", user) }.bodyAsText()) as Json.Object).getArray("messages")!!.single() as Json.Object)
        line("alice").let { assertEquals("calm", it.getString("mood")); assertEquals("to self", it.getString("note")) }
        line("bob").let { assertEquals("calm", it.getString("mood")); assertNull(it["note"]) }
        room.stop()
    }

    private suspend fun ApplicationTestBuilder.send(path: String, body: String, user: String?) = client.post("/api/rooms/$path") {
        user?.let { header("X-User", it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }
    private suspend fun ApplicationTestBuilder.read(path: String, user: String?) = client.get("/api/rooms/$path") { user?.let { header("X-User", it) } }
    private suspend fun HttpResponse.reason() = (Json.parse(bodyAsText()) as Json.Object).getString("reason")

    @Test fun `every room of a kind, built on first use, none for an unknown id`() = testApplication {
        val tag = "kind-${System.nanoTime()}"
        val built = mutableListOf<String>()
        routing { route("/api") { rooms({ id -> if (id.startsWith(tag)) Club(id, "open").also { built += id } else null }, principal) } }
        assertEquals(HttpStatusCode.OK, send("$tag-a/add", """{"by":2}""", "alice").status)
        assertEquals(HttpStatusCode.OK, read("$tag-a/events", "alice").status)
        assertEquals(HttpStatusCode.OK, send("$tag-b/add", """{"by":1}""", "alice").status)
        assertEquals(listOf("$tag-a", "$tag-b"), built)
        assertEquals(2, (Lobby.get<LogicRoomTest.Counter>("$tag-a") as Club).state.n)
        send("nope/add", "{}", "alice").let { assertEquals(HttpStatusCode.NotFound, it.status); assertEquals("no_room", it.reason()) }
        assertEquals(HttpStatusCode.NotFound, send("$tag-a/set", """{"n":1}""", "alice").status)   // emitted, not posted
        assertEquals(HttpStatusCode.NotFound, read("$tag-a/history", "alice").status)   // no chat, no scroll-back
        Lobby.remove("$tag-a"); Lobby.remove("$tag-b")
    }

    @Test fun `arkad's levels, the room's role decides, before the stream starts`() = testApplication {
        val tag = "level-${System.nanoTime()}"
        routing { route("/api") { rooms({ id -> Club(id, id.substringAfterLast('-'), setOf("alice")) }, principal) } }
        val sse = createClient { install(io.ktor.client.plugins.sse.SSE) }

        // public: an anonymous reader is a spectator, the signed-in act
        sse.sse("/api/rooms/$tag-public/stream") {
            val first = incoming.first()
            assertEquals("state", first.event)
            assertNull((Json.parse(first.data!!) as Json.Object)["me"])
        }
        assertEquals(HttpStatusCode.Unauthorized, send("$tag-public/add", """{"by":1}""", null).status)
        assertEquals(HttpStatusCode.OK, send("$tag-public/add", """{"by":1}""", "bob").status)

        // open: the signed-in only
        read("$tag-open/stream", null).let { assertEquals(HttpStatusCode.Unauthorized, it.status); assertEquals("unauthenticated", it.reason()) }
        assertEquals(HttpStatusCode.Unauthorized, read("$tag-open/events", null).status)
        assertEquals(HttpStatusCode.OK, send("$tag-open/add", """{"by":1}""", "bob").status)

        // private: its members only
        read("$tag-private/stream", "bob").let { assertEquals(HttpStatusCode.Forbidden, it.status); assertEquals("forbidden", it.reason()) }
        send("$tag-private/add", """{"by":1}""", "bob").let { assertEquals(HttpStatusCode.Forbidden, it.status); assertEquals("forbidden", it.reason()) }
        assertEquals(HttpStatusCode.Unauthorized, read("$tag-private/stream", null).status)
        assertEquals(HttpStatusCode.OK, send("$tag-private/add", """{"by":1}""", "alice").status)
        sse.sse("/api/rooms/$tag-private/stream?tabId=t1", { header("X-User", "alice") }) { assertEquals("state", incoming.first().event) }
        listOf("public", "open", "private").forEach { Lobby.remove("$tag-$it") }
    }

    @Test fun `a spectator reads the stream and the scroll-back, its intents are refused`() = testApplication {
        val room = Gallery("gallery-${n++}")
        routing { route("/api") { room(room, principal) } }
        assertEquals(HttpStatusCode.OK, send("${room.id}/chat", """{"text":"hi"}""", "alice").status)
        send("${room.id}/chat", """{"text":"me too"}""", "bob").let { assertEquals(HttpStatusCode.Forbidden, it.status); assertEquals("spectator", it.reason()) }
        val history = Json.parse(read("${room.id}/history", "bob").bodyAsText()) as Json.Object
        assertEquals(listOf("hi"), history.getArray("messages")!!.map { (it as Json.Object).getString("text") })
        assertEquals(HttpStatusCode.Unauthorized, read("${room.id}/history", null).status)
        val sse = createClient { install(io.ktor.client.plugins.sse.SSE) }
        sse.sse("/api/rooms/${room.id}/stream?tabId=t1", { header("X-User", "bob") }) {
            assertEquals("bob", (Json.parse(incoming.first().data!!) as Json.Object).getObject("me")!!.getString("id"))
        }
        room.stop()
    }

    @Test fun `an anonymous stream is refused before it starts by default`() = testApplication {
        val room = ChatRoom("anon-${n++}")
        routing { route("/api") { room(room, principal) } }
        read("${room.id}/stream", null).let { assertEquals(HttpStatusCode.Unauthorized, it.status); assertEquals("unauthenticated", it.reason()) }
        room.stop()
    }
}
