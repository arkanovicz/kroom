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

    /**
     * What the writes since it was last cleared went under: the identities ([JsonView.identity]) of every view on each
     * write's path, an item removed from a `keyed` field's with them, when a browser's Room keeps one — the merge's clean door answers for the other items. None on the
     * server. A write past the views (`json[k] = v`) leaves no mark: reduce through the fields.
     */
    var dirty: MutableSet<Any>? = null

    /** A write went through the fields since a browser's Room last cleared it: the root changed. Never cleared on the server. */
    var touched: Boolean = false

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
    /** An image URL, null when none. */
    val avatar: String? get() = null
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
 * The room's decisions: one handler per intent of its [events], declared on the entry
 * (`on(ChifoumiEvents.play) { state, actor, play -> … }`), one per cancellable intent's `cancel`. Common
 * code, pure and stateless — a function of its arguments and of what the platform hands its
 * constructor (a clock, ids) — the loop's business (framing, numbering) kept out: it runs the same
 * in a test, on the server, in a browser. It decides once the intent's [Event.check] passed: what
 * the check refuses, a handler need not test again. kroom's events (`join`, `leave`, `status`) reach
 * a handler when the logic declares one, nothing otherwise; a `cancel` reads the State for the
 * deposit it withdraws.
 */
abstract class Logic<S : State>(val events: RoomEvents) {
    private val handlers = HashMap<String, (S, Actor, Event) -> Outcome<Event>>()
    private val withdrawals = HashMap<String, (S, Actor, Cancel) -> Outcome<Event>>()

    /** The handler of [entry]'s events: typed on its class, answering a Reason or what it emits. */
    @Suppress("UNCHECKED_CAST")
    protected fun <E : Event> on(entry: RoomEvents.Entry<E>, handler: (state: S, actor: Actor, event: E) -> Outcome<Event>) {
        require(events.entry(entry.name) === entry) { "'${entry.name}' is not an entry of this logic's events" }
        require(entry.name !in handlers) { "'${entry.name}' handled twice" }
        handlers[entry.name] = { state, actor, event -> handler(state, actor, event as E) }   // built by the entry's factory: an E
    }

    /** The withdrawal of [entry]'s deposit: the handler reads the State for it, `reduce` removes it from the `cancel` frame. */
    protected fun cancel(entry: RoomEvents.Entry<*>, handler: (state: S, actor: Actor, cancel: Cancel) -> Outcome<Event>) {
        require(events.entry(entry.name) === entry && entry.cancellable) { "'${entry.name}' is not a cancellable entry of this logic's events" }
        require(entry.name !in withdrawals) { "'${entry.name}' withdrawn twice" }
        withdrawals[entry.name] = handler
    }

    /** Every intent of the app handled, every cancellable one withdrawn: checked once, when the room is built or at the first decision. */
    fun checkCoverage() = coverage

    private val coverage: Unit by lazy {
        val kroom = setOf(events.status.name, events.cancel.name)
        val unhandled = events.names.filter { it !in kroom && it !in handlers }
        val unwithdrawn = events.names.filter { events.entry(it)!!.cancellable && it !in withdrawals }
        require(unhandled.isEmpty() && unwithdrawn.isEmpty()) {
            "${this::class.simpleName}: no handler for ${unhandled}, no cancel for ${unwithdrawn}"
        }
    }

    fun decide(state: S, actor: Actor, intent: Event): Outcome<Event> {
        coverage
        return if (intent is Cancel) withdrawals[intent.intent]?.invoke(state, actor, intent) ?: Reason("not_cancellable")
        else handlers[intent.name]?.invoke(state, actor, intent) ?: done
    }
}
