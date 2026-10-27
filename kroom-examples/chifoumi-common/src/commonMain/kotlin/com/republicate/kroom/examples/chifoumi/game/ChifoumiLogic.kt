package com.republicate.kroom.examples.chifoumi.game

import com.republicate.kroom.event.Actor
import com.republicate.kroom.event.Cancel
import com.republicate.kroom.event.Chat
import com.republicate.kroom.event.ChatLogic
import com.republicate.kroom.event.Outcome
import com.republicate.kroom.event.Reason
import com.republicate.kroom.event.done
import com.republicate.kroom.event.emit
import com.republicate.kson.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The arena's decisions. They read the State and never write it: who waits is `state.queue`, the fold of the
 * `match` deposits, cancels and leaves; a round's moves are fields of its match each player alone sees, put there
 * by the fold of `play`. Nothing lives here but what the platform hands in: the clock, a match id.
 */
@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
class ChifoumiLogic(clock: Clock = Clock.System, private val ids: () -> String = { Uuid.random().toString().take(8) }) : LobbyLogic() {
    private val chat = ChatLogic(clock)

    override fun chat(state: LobbyState, actor: Actor, chat: Chat): Outcome<Nothing> = this.chat.chat(state, actor, chat)

    override fun match(state: LobbyState, actor: Actor, match: Match): Outcome<Nothing> {
        if (state.myMatch(actor.id) != null) return Reason("in_match")
        if (actor.id in state.queue) return Reason("in_queue")
        val me = Player(Json.MutableObject("id" to actor.id, "name" to actor.name))
        match.player = me
        // nobody waits: the frame is the deposit, the fold queues the player
        val other = state.queue.firstOrNull() ?: return done
        match.match = MatchView.start(ids(), other, me)
        return done
    }

    override fun play(state: LobbyState, actor: Actor, play: Play): Outcome<Round> {
        val match = state.myMatch(actor.id) ?: return Reason("not_in_match")
        if (actor.id in match.played) return Reason("already_played")
        play.player = actor.id
        // the other has not played: the frame is the deposit, the fold puts the move in the match
        val (next, round) = match.resolve(actor.id, play.move!!) ?: return done
        return emit(Round().also { it.match = next; it.round = round })
    }

    override fun cancelMatch(state: LobbyState, actor: Actor, cancel: Cancel): Outcome<Nothing> =
        if (actor.id in state.queue) done else Reason("nothing_to_cancel")

    override fun cancelPlay(state: LobbyState, actor: Actor, cancel: Cancel): Outcome<Round> =
        if (state.myMatch(actor.id)?.played?.contains(actor.id) == true) done else Reason("nothing_to_cancel")

    override fun leave(state: LobbyState, user: Actor): Outcome<Match> {
        val match = state.myMatch(user.id) ?: return done
        // the other's move of the round in progress goes with the match
        return emit(Match().also { it.match = match.forfeit(user.id) })
    }
}
