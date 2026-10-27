package com.republicate.kroom.event

import com.republicate.kson.Json

/**
 * The room's data: a view over its JSON, a viewer's view of it the snapshot every stream begins with.
 * Its fields are written in one place, [reduce], from a frame — declare them `private set` — so the
 * logic can only read it, and the State is the fold of the frames, on the server as in the browser.
 * A secret is a field too, selective or hidden: nothing lives beside the State.
 */
abstract class State(json: Json.MutableObject = Json.MutableObject()) : JsonView(json) {
    /** kroom's presence, a field like any other: who is in the room, kept from `join`, `leave` and `status`. */
    val users by keyed(::UserPresence)

    /** One frame into the State: the arrows of the grammar, read right to left. A room's State calls `super` first, for presence. */
    open fun reduce(event: Event) {
        when (event) {
            is Join -> event.user?.let { users.add(it) }
            is Leave -> event.user?.let { users.remove(it.id) }
            is Status -> event.user?.let { users[it] }?.status = event.status
            else -> {}
        }
    }

    fun describe(): Json.Array = Json.Array(fields.map { it.toJson() })
}

/** Who acts: a user, by id and name, from one of its clients. The server's connection implements it. */
interface Actor {
    val id: String
    val name: String
    val client: String?
}

/**
 * What the logic answers: a [Reason] (the reply, nothing framed), or what it [Emit]s. `E` is the event's
 * output alphabet, so `Outcome<Round>` reads as the grammar's `play -> round`; a [Reason] is an
 * `Outcome<Nothing>`, good for any.
 */
sealed interface Outcome<out E : Event>

/** The intent is framed (its deposit, if any, is in the State), then [events], each reduced into the State as it goes. */
class Emit<out E : Event>(val events: List<E>) : Outcome<E>

/** The intent's frame, then these. */
fun <E : Event> emit(vararg events: E): Emit<E> = Emit(events.toList())
/** The intent's own frame and nothing more. (A vararg of an inferred `Nothing` is an array the JVM refuses: hence a value.) */
val done: Emit<Nothing> = Emit(emptyList())

/**
 * The room's decisions: the alphabet it speaks, and what one intent answers on one State. Common
 * code, pure and stateless — a function of its arguments and of what the platform hands its
 * constructor (a clock, ids) — the loop's business (framing, numbering) kept out: it runs the same
 * in a test, on the server, in a browser. Presence (`join`, `leave`, `status`) and `cancel` arrive
 * here as events too; a `cancel` reads the State for the deposit it withdraws.
 */
interface Logic<S : State> {
    val registry: Registry
    fun decide(state: S, actor: Actor, intent: Event): Outcome<Event>
}
