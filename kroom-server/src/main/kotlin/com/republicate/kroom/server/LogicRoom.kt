package com.republicate.kroom.server

import com.republicate.kroom.event.Cancel
import com.republicate.kroom.event.Client
import com.republicate.kroom.event.Event
import com.republicate.kroom.event.Emit
import com.republicate.kroom.event.Join
import com.republicate.kroom.event.Leave
import com.republicate.kroom.event.Logic
import com.republicate.kroom.event.Outcome
import com.republicate.kroom.event.Presence
import com.republicate.kroom.event.Reason
import com.republicate.kroom.event.Stage
import com.republicate.kroom.event.State
import com.republicate.kroom.event.Status
import com.republicate.kroom.event.UserPresence
import com.republicate.kson.Json

/**
 * A room run by a [Logic]: the data is a [State], the fold of the frames; an intent is its check,
 * then one call of the logic, on the loop, and its [Outcome] is what the step does — a [Reason] is the reply, nothing
 * framed, nothing changed; an [Emit] frames the intent, then each event, each reduced into the State
 * as it goes. Presence (`join`, `leave`, `status`) and `cancel` are the room's: it builds them and
 * hands them to the logic like intents. Nothing is held here: a deposit is the State's.
 * Every snapshot carries `me`.
 */
open class LogicRoom<S : State>(id: String, override var state: S, val logic: Logic<S>, lobby: Lobby<*, *>? = null) : Room<S>(id, logic.events, lobby) {
    // loop-confined: the current step's emission, kept until the intent itself is framed
    private var outcome: Emit<Event>? = null

    init {
        events.all.forEach { name ->
            events.frame(name, Json.MutableObject())!!.checkStates.forEach { require(it.isInstance(state)) { "'$name' is checked on ${it.simpleName}, room '$id' holds ${state::class.simpleName}" } }
        }
        logic.checkCoverage()
        // every entry of the app, the room's events (`event(::E)`) too: a client posts the postable ones, server code
        // acts the others (a store's answer, as the request's author); the check first, on the loop: the State it
        // read is the one the logic decides on
        val kroom = setOf(events.join.name, events.leave.name, events.status.name, events.cancel.name)
        events.all.filter { it !in kroom }.forEach { name -> on(events.entry(name)!!.factory) { actor, intent -> intent.check(state, actor) ?: settle(intent, logic.decide(state, actor, intent)) } }
        on(::Status) { actor, status -> status(actor, status) }
        on(::Cancel) { actor, cancel -> cancel(actor, cancel) }
        // the logic hears every connection: `first` and `last` say which are a user's
        on(::Join) { actor, join -> hook(actor, join) }
        on(::Leave) { actor, leave -> hook(actor, leave) }
    }

    /** A presence event: the logic cannot refuse it, a reason or a failure there is the logic's error — the frame goes, its pair too. */
    private fun hook(actor: Actor, event: Event): Reason? {
        try {
            settle(event, logic.decide(state, actor, event))?.let { logger.error("'${event.name}' refused by the logic in room '$id': $it") }
        } catch (e: Exception) {
            logger.error("'${event.name}' failed in the logic of room '$id'", e)
        }
        return null
    }

    private fun status(actor: Actor, status: Status): Reason? {
        // only the room says LOST: its own frame had no door, it is still BORN
        if (status.status == Presence.LOST && status.stage != Stage.BORN) return Reason("invalid", Json.Object("field" to "status"))
        actor.status = status.status
        val user = actor.user ?: return null
        status.user = user.userId
        status.client = Client().also { it.id = actor.tab; it.kind = actor.kind; it.status = actor.status }
        status.status = presence(user).status!!
        return settle(status, logic.decide(state, actor, status))
    }

    /** A cancel names a cancellable intent; what there is to withdraw, the logic reads in the State. */
    private fun cancel(actor: Actor, cancel: Cancel): Reason? {
        if (events.entry(cancel.intent)?.cancellable != true) return Reason("not_cancellable")
        cancel.user = actor.id
        return settle(cancel, logic.decide(state, actor, cancel))
    }

    /** The verdict of an outcome; an emission is kept for [follow], once the intent itself is framed. */
    private fun settle(intent: Event, outcome: Outcome<Event>): Reason? = when (outcome) {
        is Reason -> outcome
        is Emit -> {
            val stray = outcome.events.firstOrNull { it.name !in events.all }
            if (stray != null) Reason("error").also { logger.error("'${intent.name}' emits '${stray.name}', undeclared, in room '$id'") }
            else { this.outcome = outcome; null }
        }
    }

    override fun follow(intent: Event) {
        val emitted = outcome ?: return
        outcome = null
        // an emission is its intent's: it carries the author (a USERS frame finds its tabs, a store its requester)
        emitted.events.forEach { e -> if (e.meta.from == null) { e.meta.from = intent.meta.from; e.meta.tab = intent.meta.tab }; emit(e) }
    }

    override fun fold(frame: Event) = state.reduce(frame)

    override fun stateToJson(): Json.Object = state.view(null)

    /** The State as the viewer's user sees it (presence included: `users` is the fold of the presence frames), plus who
     *  the viewer is (`me`, absent for a spectator: a joiner is not in `users` before its own `join`, which follows). */
    override fun stateToJson(actor: Actor): Json.Object = Json.MutableObject(state.view(actor.user?.userId)).also {
        actor.user?.let { u -> it["me"] = presence(u).json }
    }

    override fun describe(): Json.Object = Json.Object("state" to state.describe(), "events" to events.describe())

    // the fold of the room's own presence frames, in their order
    override fun presences(): List<UserPresence> = state.users.map { UserPresence(it.json.copy()) }

    override fun guests(): Int = state.guests ?: 0

    /** A presence entry, for a test or a lobby. */
    fun presenceOf(userId: String): UserPresence? = users[userId]?.let(::presence)
}
