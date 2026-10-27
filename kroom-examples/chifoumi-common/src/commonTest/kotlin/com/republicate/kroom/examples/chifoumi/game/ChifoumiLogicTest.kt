package com.republicate.kroom.examples.chifoumi.game

import com.republicate.kroom.event.Actor
import com.republicate.kroom.event.Cancel
import com.republicate.kroom.event.Emit
import com.republicate.kroom.event.Event
import com.republicate.kroom.event.Leave
import com.republicate.kroom.event.Outcome
import com.republicate.kroom.event.Reason
import com.republicate.kroom.event.done
import com.republicate.kson.Json
import kotlin.test.*

/** Pins the arena's decisions as a function of the State, the deposits in it, without a room. */
class ChifoumiLogicTest {

    private class Who(override val id: String, override val name: String = id, override val client: String? = "t1") : Actor
    private val alice = Who("a", "Alice")
    private val bob = Who("b", "Bob")
    private var n = 0
    private val logic = ChifoumiLogic(ids = { "m${++n}" })

    private fun json(vararg pairs: Pair<String, Any?>) = Json.MutableObject(*pairs)
    private fun LobbyState.fold(vararg frames: Event) = frames.forEach { reduce(it) }
    /** As the room does: the decision, then the intent's frame and what it emits, folded. */
    private fun LobbyState.act(actor: Who, intent: Event): Outcome<Event> {
        if (intent is Cancel) intent.user = actor.id   // the room's
        return logic.decide(this, actor, intent).also { if (it is Emit) { fold(intent); it.events.forEach { e -> fold(e) } } }
    }

    @Test fun `wanting a match is a deposit, the second one pairs, and the queue is the fold`() {
        val state = LobbyState()
        val wantA = Match()
        assertSame(done, logic.decide(state, alice, wantA))
        assertEquals("a", wantA.player?.id); assertNull(wantA.match)
        state.fold(wantA)
        assertEquals(listOf("a"), state.queue.map { it.id })
        assertEquals("in_queue", (logic.decide(state, alice, Match()) as Reason).key)

        val wantB = Match()
        assertSame(done, logic.decide(state, bob, wantB))
        val m = wantB.match!!
        assertEquals("m1", m.id)
        assertEquals("a", m.player1); assertEquals("b", m.player2); assertEquals("Alice", m.name1); assertEquals("Bob", m.name2)
        state.fold(wantB)
        assertTrue(state.queue.isEmpty())
        assertEquals(listOf(m.id), state.matches.map { it.id })
        assertEquals("in_match", (logic.decide(state, bob, Match()) as Reason).key)
    }

    @Test fun `a move is a deposit, the second resolves the round, and the stats are the fold`() {
        val state = LobbyState()
        state.act(alice, Match())
        state.act(bob, Match())
        val rock = Play(json("move" to "ROCK"))
        assertSame(done, state.act(alice, rock))
        assertEquals("a", rock.player)
        assertEquals(Move.ROCK, state.myMatch("a")!!.move1)   // the fold put it in the match
        fun move1(viewer: String?) = (state.view(viewer).getObject("matches")!!.values.single() as Json.Object).getString("move1")
        assertEquals(listOf("ROCK", null, null), listOf(move1("a"), move1("b"), move1(null)))   // alice's view alone has it
        assertEquals("already_played", (state.act(alice, Play(json("move" to "WELL"))) as Reason).key)
        val out = state.act(bob, Play(json("move" to "PAPER")))
        assertIs<Emit<Event>>(out)
        val round = out.events.single() as Round
        assertEquals("b", round.round.getString("winner")); assertEquals(1, round.match.score2); assertEquals(2, round.match.round)
        assertNull(state.myMatch("a")!!.move1)   // the round's match starts afresh
        assertEquals("nothing_to_cancel", (state.act(alice, Cancel(json("intent" to "play"))) as Reason).key)   // the round took it
        assertEquals(1, state.stats!!.rock); assertEquals(1, state.stats!!.paper); assertEquals(0, state.stats!!.played)
        assertEquals(1, state.matches.single().score2)
        assertEquals("not_in_match", (logic.decide(state, Who("c"), Play(json("move" to "ROCK"))) as Reason).key)
    }

    @Test fun `a withdrawn move frees the round, a leave forfeits the match`() {
        val state = LobbyState()
        state.act(alice, Match())
        assertSame(done, state.act(alice, Cancel(json("intent" to "match"))))
        assertTrue(state.queue.isEmpty())
        assertEquals("nothing_to_cancel", (state.act(alice, Cancel(json("intent" to "match"))) as Reason).key)
        state.act(alice, Match())
        state.act(bob, Match())
        state.act(alice, Play(json("move" to "ROCK")))
        assertSame(done, state.act(alice, Cancel(json("intent" to "play"))))
        assertNull(state.myMatch("a")!!.move1)
        assertSame(done, state.act(alice, Play(json("move" to "WELL"))))   // not already_played
        val gone = logic.decide(state, bob, Leave())
        assertIs<Emit<Event>>(gone)
        val ended = (gone.events.single() as Match).match!!
        assertEquals(true, ended.finished); assertEquals("a", ended.winner); assertNull((gone.events.single() as Match).player)
        state.fold(gone.events.single())
        assertTrue(state.matches.isEmpty())   // alice's move went with it
        assertEquals(1, state.stats!!.played)   // forfeited, still a match played
        assertEquals("not_in_match", (logic.decide(state, alice, Play(json("move" to "ROCK"))) as Reason).key)
    }
}
