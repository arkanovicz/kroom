package com.republicate.kroom.examples.chifoumi.game

import com.republicate.kroom.event.Actor
import com.republicate.kroom.event.Cancel
import com.republicate.kroom.event.Chat
import com.republicate.kroom.event.Emits
import com.republicate.kroom.event.Event
import com.republicate.kroom.event.Join
import com.republicate.kroom.event.Leave
import com.republicate.kroom.event.Logic
import com.republicate.kroom.event.Outcome
import com.republicate.kroom.event.Reason
import com.republicate.kroom.event.Registry
import com.republicate.kroom.event.Status
import com.republicate.kroom.event.done

/**
 * The typed layer over the grammar: one member per arrow, typed by what it may emit, one per cancellable
 * intent's `cancel`, and the dispatch. Written by hand today in the shape a generator will write tomorrow
 * (its golden).
 */
abstract class LobbyLogic : Logic<LobbyState> {
    companion object {
        /** The alphabet, the browser's too. */
        val registry = Registry(listOf(::Chat, ::Match, ::Play), leave = Emits(::Match))
    }
    override val registry get() = Companion.registry

    abstract fun chat(state: LobbyState, actor: Actor, chat: Chat): Outcome<Nothing>
    abstract fun match(state: LobbyState, actor: Actor, match: Match): Outcome<Nothing>
    abstract fun play(state: LobbyState, actor: Actor, play: Play): Outcome<Round>
    abstract fun cancelMatch(state: LobbyState, actor: Actor, cancel: Cancel): Outcome<Nothing>
    abstract fun cancelPlay(state: LobbyState, actor: Actor, cancel: Cancel): Outcome<Round>
    open fun join(state: LobbyState, user: Actor): Outcome<Nothing> = done
    open fun leave(state: LobbyState, user: Actor): Outcome<Match> = done
    open fun status(state: LobbyState, user: Actor, status: Status): Outcome<Nothing> = done

    override fun decide(state: LobbyState, actor: Actor, intent: Event): Outcome<Event> = when (intent) {
        is Chat -> chat(state, actor, intent)
        is Match -> match(state, actor, intent)
        is Play -> play(state, actor, intent)
        is Cancel -> when (intent.intent) {
            "match" -> cancelMatch(state, actor, intent)
            "play" -> cancelPlay(state, actor, intent)
            else -> Reason("not_cancellable")
        }
        is Join -> join(state, actor)
        is Leave -> leave(state, actor)
        is Status -> status(state, actor, intent)
        else -> Reason("unknown")
    }
}
