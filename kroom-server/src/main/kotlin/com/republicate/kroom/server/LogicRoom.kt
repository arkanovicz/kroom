package com.republicate.kroom.server

import com.republicate.kroom.event.Cancel
import com.republicate.kroom.event.Client
import com.republicate.kroom.event.Event
import com.republicate.kroom.event.Fired
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
 * A room run by a [Logic]: the marking is a [State], the fold of the frames; an intent is one
 * call of the rule on the loop, and its [Outcome] is what the step does — a [Reason] is the reply,
 * nothing framed, nothing changed; a [Fired] frames the intent, then each event, each reduced into
 * the state as it goes, then moves the tokens (held, released). Presence (`join`, `leave`,
 * `status`) and `cancel` are the room's: it builds them and hands them to the rule like intents.
 * Every snapshot carries `users` (presence) and `pending` (the viewer's held intents, by name).
 */
open class LogicRoom<S : State>(id: String, override var state: S, val logic: Logic<S>) : Room<S>(id, logic.registry) {
    // loop-confined: the tokens, by user then intent name; the current step's firing
    private val held = HashMap<String, MutableMap<String, Event>>()
    private var outcome: Fired<Event>? = null

    init {
        registry.names.forEach { name -> on(registry.factory(name)!!) { actor, intent -> run(actor, intent) } }
        on(::Status) { actor, status -> status(actor, status) }
        on(::Cancel) { actor, cancel -> cancel(actor, cancel) }
        // the rule hears a user, not a connection
        on(::Join) { actor, join -> if (join.first == true) hook(actor, join, registry.join.names) else null }
        on(::Leave) { actor, leave ->
            if (leave.last != true) return@on null
            held.remove(actor.id)   // a departure withdraws what the user held
            hook(actor, leave, registry.leave.names)
        }
    }

    private fun run(actor: Actor, intent: Event): Reason? {
        if (intent.pending && held[actor.id]?.containsKey(intent.name) == true) return Reason("already_pending")
        return settle(intent, logic.fire(state, actor, intent), intent.fires.names)
    }

    /** A presence event: the rule's answer cannot refuse it, a reason there is the rule's error. */
    private fun hook(actor: Actor, event: Event, allowed: Set<String>): Reason? {
        settle(event, logic.fire(state, actor, event), allowed)?.let { logger.error("'${event.name}' refused by the rule in room '$id': $it") }
        return null
    }

    private fun status(actor: Actor, status: Status): Reason? {
        actor.status = status.status
        val user = actor.user ?: return null
        status.user = user.userId
        status.client = Client().also { it.id = actor.tab; it.kind = actor.kind; it.status = actor.status }
        status.status = presence(user).status!!
        return settle(status, logic.fire(state, actor, status), registry.status.names)
    }

    private fun cancel(actor: Actor, cancel: Cancel): Reason? {
        val intent = held[actor.id]?.get(cancel.intent) ?: return Reason("not_pending")
        cancel.user = actor.id
        return settle(cancel, logic.cancel(state, actor, intent), intent.fires.names)
            ?: null.also { held[actor.id]?.remove(cancel.intent) }
    }

    /** The verdict of an outcome; a firing is kept for [follow], once the intent itself is framed. */
    private fun settle(intent: Event, outcome: Outcome<Event>, allowed: Set<String>): Reason? = when (outcome) {
        is Reason -> outcome
        is Fired -> {
            val stray = outcome.events.firstOrNull { it.name !in allowed }
            when {
                stray != null -> Reason("error").also { logger.error("'${intent.name}' fires '${stray.name}', undeclared, in room '$id'") }
                outcome.held && !intent.pending -> Reason("error").also { logger.error("'${intent.name}' held, not declared pending, in room '$id'") }
                else -> { this.outcome = outcome; null }
            }
        }
    }

    override fun follow(intent: Event) {
        val fired = outcome ?: return
        outcome = null
        val user = intent.meta.from
        if (fired.held && user != null) { held.getOrPut(user) { HashMap() }[intent.name] = intent; intent.meta.held = true }
        fired.released.forEach { held[it]?.remove(intent.name) }
        fired.events.forEach { emit(it) }
    }

    override fun fold(frame: Event) = state.reduce(frame)

    override fun stateToJson(): Json.Object = state.json

    /** The marking, plus what presence knows, what the viewer holds, and who the viewer is (`me`, absent for a spectator). */
    override fun stateToJson(actor: Actor): Json.Object = Json.MutableObject(state.json).also {
        it["users"] = Json.Array(users.values.map { u -> presence(u).json })
        it["pending"] = Json.Array(held[actor.id]?.keys?.toList() ?: emptyList<String>())
        actor.user?.let { u -> it["me"] = presence(u).json }
    }

    override fun describe(): Json.Object = Json.Object("state" to state.describe(), "events" to registry.describe())

    /** A presence entry, for a test or a lobby. */
    fun presenceOf(userId: String): UserPresence? = users[userId]?.let(::presence)
}
