package com.republicate.kroom.event

import com.republicate.kson.Json
import kotlin.reflect.KClass
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Where an applied event goes when nothing names its users: the room, or the emitter. Legacy of the `on()` path: `to` says it now. */
enum class Audience { ROOM, USERS }

/**
 * One life, in order. A room-born event starts at APPLIED; a rejection is the verdict on the
 * reply and goes no further.
 */
enum class Stage { BORN, ACCEPTED, APPLIED, REJECTED, PERSISTED }

/** What the browser localizes: a key and its arguments, never a sentence. As an [Outcome], the logic refuses: nothing framed. */
class Reason(val key: String, val args: Json.Object = Json.Object()) : Outcome<Nothing> {
    override fun toString() = if (args.isEmpty()) key else "$key $args"
}

/**
 * The one thing that goes through E, from a gesture to a row: a name, a payload, metadata.
 * The payload is the JSON, the class is a typed view over it, and the payload alone is the wire:
 * a frame is `{...payload}`, its position on the room's channel is the SSE id. The metadata
 * (who, when, the stage, a reason) is the object's, server-side; a `_` key a client sends is
 * dropped at birth. A subclass is a declaration — name, fields, its [check] — data a room's
 * [RoomEvents] hands to a browser without Kotlin (the check aside: code, common, run by the browser
 * before posting and by the room first on its loop).
 *
 * The stages are the lifecycle, walked in order on this very object; a step out of order is an
 * error of the object, not a rule of a room. Who steps: validation [accept]s (stateless: a
 * session, a permission, the declared shape and nothing else), a room [apply]s or [reject]s (with
 * state, on its loop), the loop [sequence]s it (a seq), a store [persist]s. A reaction never births an
 * event: only a gesture, a clock, or a room's apply does.
 */
abstract class Event(
    val name: String,
    val audience: Audience = Audience.ROOM,
    val permission: String? = null,
    json: Json.MutableObject = Json.MutableObject()
) : JsonView(json) {

    val meta = Meta(Json.MutableObject())

    init { json.remove(META) }

    val stage: Stage get() = meta.stage ?: Stage.BORN

    /**
     * The stateful half of validation, declared in the event's body, typed on the State it reads:
     * `init { check { state: ChifoumiState, actor -> … } }`. It reads the `in` fields and the State, never an
     * `out` field (the logic fills those later); no I/O. Null: the logic may decide. Checks compose like `init`
     * blocks: a subclass's is run after its parent's, and the first refusal wins.
     */
    protected inline fun <reified S : State> check(noinline body: (state: S, actor: Actor) -> Reason?) {
        checkers += Checker(S::class) { state, actor -> body(state as S, actor) }
    }

    @PublishedApi internal val checkers = mutableListOf<Checker>()

    @PublishedApi internal class Checker(val state: KClass<out State>, val body: (State, Actor) -> Reason?)

    /** The State classes the checks read, parent's first: a room refuses at construction an event checked on another State. */
    val checkStates: List<KClass<out State>> get() = checkers.map { it.state }

    /** The checks on that State, by that actor, parent's first: the first refusal, or null. */
    fun check(state: State, actor: Actor): Reason? = state.read {
        checkers.firstNotNullOfOrNull { c ->
            kotlin.check(c.state.isInstance(state)) { "$name: checked on ${c.state.simpleName}, not ${state::class.simpleName}" }
            c.body(state, actor)
        }
    }

    /** Validation's half: who, from which tab, and a payload of the declared shape, nothing else. */
    fun accept(from: String, tab: String? = null): Event = step(Stage.ACCEPTED, Stage.BORN) {
        validate()?.let { throw Rejected(it) }
        meta.from = from
        meta.tab = tab
    }

    /** The loop's half. A room-born event (`match`, `activity`) applies from BORN: it had no door. */
    @OptIn(ExperimentalTime::class)
    fun apply(): Event = step(Stage.APPLIED, Stage.ACCEPTED, Stage.BORN) {
        meta.at = Clock.System.now().toEpochMilliseconds()
    }

    fun reject(reason: Reason): Event = step(Stage.REJECTED, Stage.BORN, Stage.ACCEPTED) {
        meta.reason = reason.key
        meta.args = reason.args
    }

    /** Numbered and on the wire, once; only an applied event is. */
    fun sequence(seq: Long): Event {
        kotlin.check(stage == Stage.APPLIED) { "$name: $stage → sequence" }
        kotlin.check(meta.seq == null) { "$name: sequenced twice" }
        meta.seq = seq
        return this
    }

    val sequenced: Boolean get() = meta.seq != null

    fun persist(): Event = step(Stage.PERSISTED, Stage.APPLIED) { kotlin.check(sequenced) { "$name: persisted before sequenced" } }

    private inline fun step(to: Stage, vararg from: Stage, stamp: () -> Unit): Event {
        kotlin.check(stage in from) { "$name: $stage → $to" }
        stamp()
        meta.stage = to
        return this
    }

    /** The users this frame is for; none means the room. */
    val to: List<String> get() = meta.to?.map { it as String } ?: emptyList()

    /** The declaration as data: what a room's [RoomEvents] serves, what a browser without Kotlin reads. */
    fun describe(): Json.Object = Json.Object(
        "name" to name,
        "audience" to audience.name,
        "permission" to permission,
        "fields" to Json.Array(fields.map { it.toJson() })
    )

    override fun toString() = "$name ${json}"

    class Rejected(val reason: Reason) : RuntimeException(reason.toString())

    /** The metadata, the object's own: never on the wire. */
    class Meta(json: Json.MutableObject) : JsonView(json) {
        var stage by enum<Stage>()
        var from by string()     // the author's id — a user's, or `discord:<snowflake>` for a bridge
        var tab by string()      // the emitting client, for the trace
        var at by long()         // applied, epoch millis
        var seq by long()        // sequenced, the room's order
        var to by array()        // the users this frame is for; none means the room
        var reason by string()   // rejected: a key the browser localizes…
        var args by obj()        // …with these
    }

    companion object {
        /** The key a client must not use: whatever it holds is dropped at birth. */
        const val META = "_"
    }
}

/** This frame for these users (every client of theirs), not the room. */
fun <E : Event> E.to(vararg users: String): E = apply { meta.to = Json.Array(users.toList()) }
