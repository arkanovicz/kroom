package com.republicate.kroom.examples.chifoumi.game

import com.republicate.kroom.event.Cancel
import com.republicate.kroom.event.Event
import com.republicate.kroom.event.Stage
import com.republicate.kroom.event.Store
import com.republicate.kroom.server.Actor
import com.republicate.kroom.server.ChatRoom
import com.republicate.kroom.server.MemoryChatHistory
import com.republicate.kroom.server.User
import com.republicate.kroom.server.Users
import com.republicate.kson.Json
import io.ktor.sse.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.test.*

/** Pins the arena's frames as a room plays them (the pages' golden); its decisions alone: chifoumi-common's `ChifoumiLogicTest`. */
class LobbyTest {

    private fun json(vararg pairs: Pair<String, Any?>) = Json.MutableObject(*pairs)

    @BeforeTest fun clean() { Users.clear() }


    /** Ids and the clock fixed: the recording is the same at every run. */
    @OptIn(ExperimentalTime::class)
    private class Arena(id: String) : ChatRoom<LobbyState>(id, MemoryChatHistory(), LobbyState(),
        ChifoumiLogic(object : Clock { override fun now() = Instant.parse("2026-10-27T12:00:00Z") }, ids = { "m1" })) {
        override val leaveGrace = 0L
        override fun shouldCloseWhenEmpty() = false
    }
    private fun user(id: String, name: String) = Users.getOrCreate(id, name)
    private fun actor(user: User, tab: String = "t1") = Actor(Actor.connectionId(user, tab), user)
    private suspend fun Channel<ServerSentEvent>.next(): ServerSentEvent = withTimeout(2000) { receive() }

    @Test fun `a match from the lobby to its end as bob's tab sees it`() = runBlocking {
        val room = Arena("arena")
        val a = actor(user("a", "Alice")); val b = actor(user("b", "Bob"))
        val ca = room.join(a)
        val cb = room.join(b)
        suspend fun act(who: Actor, e: Event): Event = room.act(who, e).await().also { assertNotEquals(Stage.REJECTED, it.stage, it.meta.reason) }
        act(a, Match())
        act(b, Match())
        act(a, com.republicate.kroom.event.Chat(json("text" to "gl hf")))
        act(a, Play(json("move" to "ROCK")))
        act(b, Play(json("move" to "PAPER")))
        act(b, Play(json("move" to "WELL")))
        act(b, Cancel(json("intent" to "play")))
        act(b, Play(json("move" to "SCISSORS")))
        act(a, Play(json("move" to "ROCK")))
        room.leave(a)

        val seen = mutableListOf<ServerSentEvent>()
        while (true) { val f = cb.tryReceive().getOrNull() ?: break; seen += f }
        assertEquals(listOf("state", "join", "match", "match", "chat", "play", "play", "round", "play", "cancel", "play", "play", "round", "leave", "match"), seen.map { it.event })
        // commutation: the fold of what bob's tab received is the snapshot bob would get now
        val final = room.stateToJson(b)
        val store = Store(LobbyState(), LobbyEvents)
        seen.forEach { store.fold(it.event!!, it.data!!) }
        assertEquals(final, store.json)   // kson compares objects as maps, numbers by value
        // the recording, the golden of the browser's fold (chifoumi-common's FoldTest) and of the pages (src/test/js): refresh it when the wire changes
        val recording = seen.joinToString("") { """{"id":${it.id},"event":"${it.event}","data":${it.data}}""" + "\n" } + """{"event":"final","data":$final}""" + "\n"
        File("build/frames.jsonl").writeText(recording)
        val golden = File("../chifoumi-common/src/commonTest/resources/frames.jsonl")
        val refresh = "refresh the golden: cp build/frames.jsonl $golden"
        assertTrue(golden.exists(), refresh)
        assertEquals(seen.map { it.event }, golden.readLines().filter { it.isNotBlank() }.map { (Json.parse(it) as Json.Object).getString("event") }.filter { it != "final" }, refresh)
        val snapshot = Json.parse(seen[0].data!!) as Json.Object
        // presence is the fold of its frames: the joiner is not in its own snapshot, its `join` follows
        assertEquals(listOf("a"), snapshot.getObject("users")!!.keys.toList())
        // who played is everyone's, which move its player's alone
        assertEquals("a", (Json.parse(seen[5].data!!) as Json.Object).getString("player"))
        assertNull((Json.parse(seen[5].data!!) as Json.Object)["move"])
        assertEquals("PAPER", (Json.parse(seen[6].data!!) as Json.Object).getString("move"))
        val fromA = generateSequence { ca.tryReceive().getOrNull() }.toList().filter { it.event == "play" }.map { Json.parse(it.data!!) as Json.Object }
        assertEquals(listOf("ROCK", null, null, null, "ROCK"), fromA.map { it.getString("move") })
        // the forfeit ends the match for everyone
        val last = Json.parse(seen.last().data!!) as Json.Object
        assertEquals(true, last.getObject("match")!!.getBoolean("finished")); assertEquals("b", last.getObject("match")!!.getString("winner"))
        room.stop()
    }
}
