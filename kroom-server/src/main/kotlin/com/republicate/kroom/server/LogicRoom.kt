package com.republicate.kroom.server

import com.republicate.kroom.event.Cancel
import com.republicate.kroom.event.Client
import com.republicate.kroom.event.Event
import com.republicate.kroom.event.Emit
import com.republicate.kroom.event.Join
import com.republicate.kroom.event.Leave
import com.republicate.kroom.event.Logic
import com.republicate.kroom.event.Outcome
import com.republicate.kroom.event.Reason
import com.republicate.kroom.event.State
import com.republicate.kroom.event.Status
import com.republicate.kroom.event.UserPresence
import com.republicate.kson.Json

/**
 * A room run by a [Logic]: the data is a [State], the fold of the frames; an intent is one call of
 * the logic on the loop, and its [Outcome] is what the step does — a [Reason] is the reply, nothing
 * framed, nothing changed; an [Emit] frames the intent, then each event, each reduced into the State
 * as it goes. Presence (`join`, `leave`, `status`) and `cancel` are the room's: it builds them and
 * hands them to the logic like intents. Nothing is held here: a deposit is the State's.
 * Every snapshot carries `me`.
 */
open class LogicRoom<S : State>(id: String, override var state: S, val logic: Logic<S>) : Room<S>(id, logic.registry) {
    // loop-confined: the current step's emission, kept until the intent itself is framed
    private var outcome: Emit<Event>? = null

    init {
        registry.names.forEach { name -> on(registry.factory(name)!!) { actor, intent -> settle(intent, logic.decide(state, actor, intent), intent.emits.names) } }
        on(::Status) { actor, status -> status(actor, status) }
        on(::Cancel) { actor, cancel -> cancel(actor, cancel) }
        // the logic hears a user, not a connection
        on(::Join) { actor, join -> if (join.first == true) hook(actor, join, registry.join.names) else null }
        on(::Leave) { actor, leave -> if (leave.last == true) hook(actor, leave, registry.leave.names) else null }
    }

    /** A presence event: the logic cannot refuse it, a reason there is the logic's error. */
    private fun hook(actor: Actor, event: Event, allowed: Set<String>): Reason? {
        settle(event, logic.decide(state, actor, event), allowed)?.let { logger.error("'${event.name}' refused by the logic in room '$id': $it") }
        return null
    }

    private fun status(actor: Actor, status: Status): Reason? {
        actor.status = status.status
        val user = actor.user ?: return null
        status.user = user.userId
        status.client = Client().also { it.id = actor.tab; it.kind = actor.kind; it.status = actor.status }
        status.status = presence(user).status!!
        return settle(status, logic.decide(state, actor, status), registry.status.names)
    }

    /** A cancel names a cancellable intent; what there is to withdraw, the logic reads in the State. It may emit what that intent emits. */
    private fun cancel(actor: Actor, cancel: Cancel): Reason? {
        val intent = registry.of(cancel.intent)?.takeIf { it.cancellable } ?: return Reason("not_cancellable")
        cancel.user = actor.id
        return settle(cancel, logic.decide(state, actor, cancel), intent.emits.names)
    }

    /** The verdict of an outcome; an emission is kept for [follow], once the intent itself is framed. */
    private fun settle(intent: Event, outcome: Outcome<Event>, allowed: Set<String>): Reason? = when (outcome) {
        is Reason -> outcome
        is Emit -> {
            val stray = outcome.events.firstOrNull { it.name !in allowed }
            if (stray != null) Reason("error").also { logger.error("'${intent.name}' emits '${stray.name}', undeclared, in room '$id'") }
            else { this.outcome = outcome; null }
        }
    }

    override fun follow(intent: Event) {
        val emitted = outcome ?: return
        outcome = null
        emitted.events.forEach { emit(it) }
    }

    override fun fold(frame: Event) = state.reduce(frame)

    override fun stateToJson(): Json.Object = state.view(null)

    /** The State as the viewer's user sees it (presence included: `users` is the fold of the presence frames), plus who
     *  the viewer is (`me`, absent for a spectator: a joiner is not in `users` before its own `join`, which follows). */
    override fun stateToJson(actor: Actor): Json.Object = Json.MutableObject(state.view(actor.user?.userId)).also {
        actor.user?.let { u -> it["me"] = presence(u).json }
    }

    override fun describe(): Json.Object = Json.Object("state" to state.describe(), "events" to registry.describe())

    /** A presence entry, for a test or a lobby. */
    fun presenceOf(userId: String): UserPresence? = users[userId]?.let(::presence)
}
