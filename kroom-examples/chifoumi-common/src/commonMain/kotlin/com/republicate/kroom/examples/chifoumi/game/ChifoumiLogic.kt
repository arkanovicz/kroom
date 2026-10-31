package com.republicate.kroom.examples.chifoumi.game

import com.republicate.kroom.event.ChatLogic
import com.republicate.kroom.event.Logic
import com.republicate.kroom.event.Reason
import com.republicate.kroom.event.done
import com.republicate.kroom.event.emit
import com.republicate.kson.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The arena's decisions, one handler per intent of [LobbyEvents], once the intent's check passed (`Match`, `Play`).
 * They read the State and never write it: who waits is `state.queue`, the fold of the `match` deposits, cancels and
 * leaves; a round's moves are fields of its match each player alone sees, put there by the fold of `play`. Nothing
 * lives here but what the platform hands in: the clock, a match id.
 */
@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
class ChifoumiLogic(clock: Clock = Clock.System, private val ids: () -> String = { Uuid.random().toString().take(8) }) : Logic<LobbyState>(LobbyEvents) {
    private val chat = ChatLogic(clock)

    init {
        on(LobbyEvents.chat) { state, actor, line -> chat.chat(state, actor, line) }

        on(LobbyEvents.match) { state, actor, match ->
            val me = Player(Json.MutableObject("id" to actor.id, "name" to actor.name))
            match.player = me
            // nobody waits: the frame is the deposit, the fold queues the player
            state.queue.firstOrNull()?.let { other -> match.match = MatchView.start(ids(), other, me) }
            done
        }

        on(LobbyEvents.play) { state, actor, play ->
            val match = state.myMatch(actor.id)!!   // the check saw to it
            play.player = actor.id
            // the other has not played: the frame is the deposit, the fold puts the move in the match
            match.resolve(actor.id, play.move!!)?.let { (next, round) -> emit(Round().also { it.match = next; it.round = round }) } ?: done
        }

        on(LobbyEvents.leave) { state, actor, _ ->
            // the other's move of the round in progress goes with the match
            state.myMatch(actor.id)?.let { match -> emit(Match().also { it.match = match.forfeit(actor.id) }) } ?: done
        }

        cancel(LobbyEvents.match) { state, actor, _ -> if (actor.id in state.queue) done else Reason("nothing_to_cancel") }

        cancel(LobbyEvents.play) { state, actor, _ ->
            if (state.myMatch(actor.id)?.played?.contains(actor.id) == true) done else Reason("nothing_to_cancel")
        }
    }
}
