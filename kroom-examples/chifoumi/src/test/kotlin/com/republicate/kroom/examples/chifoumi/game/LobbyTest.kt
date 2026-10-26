package com.republicate.kroom.examples.chifoumi.game

import com.republicate.kroom.event.Cancel
import com.republicate.kroom.event.Event
import com.republicate.kroom.event.Fired
import com.republicate.kroom.event.Reason
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
import kotlin.test.*

/** Pins the arena's rule as a function, and its frames as a room plays them (the pages' golden). */
class LobbyTest {

    private class Who(override val id: String, override val name: String = id, override val client: String? = "t1") : com.republicate.kroom.event.Actor
    private val alice = Who("a", "Alice")
    private val bob = Who("b", "Bob")

    private fun json(vararg pairs: Pair<String, Any?>) = Json.MutableObject(*pairs)
    private fun LobbyState.fold(vararg frames: Event) = frames.forEach { reduce(it) }

    @BeforeTest fun clean() { ChifoumiLogic.clear(); Users.clear() }

    @Test fun `wanting a match is a deposit, the second one pairs, and the queue is the fold`() {
        val state = LobbyState()
        val wantA = Match()
        val first = ChifoumiLogic.fire(state, alice, wantA)
        assertIs<Fired<Event>>(first); assertTrue(first.held)
        assertEquals("a", wantA.player?.id); assertNull(wantA.match)
        state.fold(wantA)
        assertEquals(listOf("a"), state.queue.map { it.id })

        val wantB = Match()
        val second = ChifoumiLogic.fire(state, bob, wantB)
        assertIs<Fired<Event>>(second); assertFalse(second.held); assertEquals(setOf("a"), second.released)
        val m = wantB.match!!
        assertEquals("a", m.player1); assertEquals("b", m.player2); assertEquals("Alice", m.name1); assertEquals("Bob", m.name2)
        state.fold(wantB)
        assertTrue(state.queue.isEmpty())
        assertEquals(listOf(m.id), state.matches.map { it.id })
        assertEquals("in_match", (ChifoumiLogic.fire(state, bob, Match()) as Reason).key)
    }

    @Test fun `a move is held, the second resolves the round, and the stats are the fold`() {
        val state = LobbyState()
        state.fold(Match().also { ChifoumiLogic.fire(state, alice, it) })
        state.fold(Match().also { ChifoumiLogic.fire(state, bob, it) })
        val rock = Play(json("move" to "ROCK"))
        assertTrue((ChifoumiLogic.fire(state, alice, rock) as Fired).held)
        assertEquals("a", rock.player)
        assertEquals("already_played", (ChifoumiLogic.fire(state, alice, Play(json("move" to "WELL"))) as Reason).key)
        val paper = Play(json("move" to "PAPER"))
        val out = ChifoumiLogic.fire(state, bob, paper)
        assertIs<Fired<Event>>(out); assertEquals(setOf("a"), out.released)
        val round = out.events.single() as Round
        assertEquals("b", round.round.getString("winner")); assertEquals(1, round.match.score2); assertEquals(2, round.match.round)
        state.fold(rock, paper, round)
        assertEquals(1, state.stats!!.rock); assertEquals(1, state.stats!!.paper); assertEquals(0, state.stats!!.played)
        assertEquals(1, state.matches.single().score2)
        assertEquals("not_in_match", (ChifoumiLogic.fire(state, Who("c"), Play(json("move" to "ROCK"))) as Reason).key)
    }

    @Test fun `a withdrawn move frees the round, a leave forfeits the match`() {
        val state = LobbyState()
        state.fold(Match().also { ChifoumiLogic.fire(state, alice, it) })
        state.fold(Match().also { ChifoumiLogic.fire(state, bob, it) })
        val rock = Play(json("move" to "ROCK"))
        ChifoumiLogic.fire(state, alice, rock)
        ChifoumiLogic.cancel(state, alice, rock)
        assertTrue((ChifoumiLogic.fire(state, alice, Play(json("move" to "WELL"))) as Fired).held)   // not already_played
        val gone = ChifoumiLogic.fire(state, bob, com.republicate.kroom.event.Leave())
        assertIs<Fired<Event>>(gone)
        assertEquals(setOf("a"), gone.released)   // alice's move of the round in progress goes with the match
        val ended = (gone.events.single() as Match).match!!
        assertEquals(true, ended.finished); assertEquals("a", ended.winner); assertNull((gone.events.single() as Match).player)
        state.fold(gone.events.single())
        assertTrue(state.matches.isEmpty())
        assertEquals(1, state.stats!!.played)   // forfeited, still a match played
        assertEquals("not_in_match", (ChifoumiLogic.fire(state, alice, Play(json("move" to "ROCK"))) as Reason).key)
    }

    // ---- the room: what the pages will see, recorded

    private class Arena(id: String) : ChatRoom<LobbyState>(id, MemoryChatHistory(), LobbyState(), ChifoumiLogic) {
        override val leaveGrace = 0L
        override fun shouldCloseWhenEmpty() = false
    }
    private fun user(id: String, name: String) = Users.getOrCreate(id, name)
    private fun actor(user: User, tab: String = "t1") = Actor(Actor.connectionId(user, tab), user)
    private suspend fun Channel<ServerSentEvent>.next(): ServerSentEvent = withTimeout(2000) { receive() }

    @Test fun `a match from the lobby to its end, as bob's tab sees it`() = runBlocking {
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
        val store = Store(LobbyState(), LobbyLogic.registry)
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
        // the deposit is visible, the move is the author's
        assertEquals("a", (Json.parse(seen[5].data!!) as Json.Object).getString("player"))
        assertNull((Json.parse(seen[5].data!!) as Json.Object)["move"])
        assertEquals("PAPER", (Json.parse(seen[6].data!!) as Json.Object).getString("move"))
        // alice saw her own move, not bob's
        val fromA = generateSequence { ca.tryReceive().getOrNull() }.toList()
        val aPlays = fromA.filter { it.event == "play" }.map { Json.parse(it.data!!) as Json.Object }
        assertEquals("ROCK", aPlays[0].getString("move")); assertNull(aPlays[1]["move"])
        // the forfeit ends the match for everyone
        val last = Json.parse(seen.last().data!!) as Json.Object
        assertEquals(true, last.getObject("match")!!.getBoolean("finished")); assertEquals("b", last.getObject("match")!!.getString("winner"))
        room.stop()
    }
}
