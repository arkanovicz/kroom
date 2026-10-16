package com.republicate.kroom.event

import com.republicate.kson.Json

/**
 * The marking: a view over the room's JSON, the snapshot every stream begins with. Its fields are
 * written in one place, [reduce], from a frame — declare them `private set` — so a transition that
 * holds the state can only read it, and the state is the fold of the frames, on the server as in
 * the browser. What is not on the wire is not here: a secret is a field of the logic.
 */
abstract class State(json: Json.MutableObject = Json.MutableObject()) : JsonView(json) {
    /** One frame into the marking: the arrows of the grammar, read right to left. */
    abstract fun reduce(frame: Event)

    fun describe(): Json.Array = Json.Array(fields.map { it.toJson() })
}

/** Who fires: a user, by id and name, from one of its clients. The server's connection implements it. */
interface Actor {
    val id: String
    val name: String
    val client: String?
}

/**
 * What a transition answers: a [Reason] not to fire (the reply, nothing framed), or what it
 * [Fired]. `F` is the transition's output alphabet, so `Outcome<Round>` reads as the grammar's
 * `play -> round`; a [Reason] is an `Outcome<Nothing>`, good for any.
 */
sealed interface Outcome<out F : Event>

/**
 * The transition fired: the intent is framed (the deposit is visible), then [events], each reduced
 * into the state as it goes. [held]: the intent's token stays (a pending intent, completed later,
 * withdrawn by `cancel`); [released]: whose tokens of this name this firing consumed.
 */
class Fired<out F : Event>(val events: List<F>, val held: Boolean = false, val released: Set<String> = emptySet()) : Outcome<F> {
    fun releasing(vararg users: String): Fired<F> = Fired(events, held, released + users)
}

/** The intent fires these. */
fun <F : Event> fired(vararg events: F): Fired<F> = Fired(events.toList())
/** The intent stays pending, firing these meanwhile. */
fun <F : Event> held(vararg events: F): Fired<F> = Fired(events.toList(), held = true)
/** The intent's own frame and nothing more. */
val done: Fired<Nothing> = Fired(emptyList())

/**
 * The firing rule of a room: the alphabet it speaks, and what one intent does to one marking.
 * A function of its arguments, the loop's business (framing, numbering, tokens) kept out: it runs
 * the same in a test, on the server, in a browser. Presence (`join`, `leave`, `status`) and
 * `cancel` arrive here as events too, room-born.
 */
interface Logic<S : State> {
    val registry: Registry
    fun fire(state: S, actor: Actor, intent: Event): Outcome<Event>
    /** A held intent withdrawn: the token is released whatever this answers, unless a [Reason]. */
    fun cancel(state: S, actor: Actor, held: Event): Outcome<Event> = done
}
