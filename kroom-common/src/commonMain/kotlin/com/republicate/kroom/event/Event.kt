package com.republicate.kroom.event

import com.republicate.kson.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Where an applied event goes: the room, or users the room names (the emitter at least). Nothing crosses rooms. */
enum class Audience { ROOM, USERS }

/**
 * One life, in order. A room-born event starts at APPLIED; a rejection is the verdict on the
 * reply and goes no further.
 */
enum class Stage { BORN, ACCEPTED, APPLIED, REJECTED, PERSISTED }

/** What the browser localizes: a key and its arguments, never a sentence. */
class Reason(val key: String, val args: Json.Object = Json.Object()) {
    override fun toString() = if (args.isEmpty()) key else "$key $args"
}

/**
 * The one thing that goes through E, from a gesture to a row: a name, a payload, metadata.
 * The payload is the JSON, the class is a typed view over it, and the payload alone is the wire:
 * a frame is `{...payload}`, its position on the room's channel is the SSE id. The metadata
 * (who, when, the stage, a reason) is the object's, server-side; a `_` key a client sends is
 * dropped at birth. A subclass is a declaration — name, audience kind, permission, fields — all
 * data, what a catalog hands to a browser without Kotlin.
 *
 * The stages are the lifecycle, walked in order on this very object; a step out of order is an
 * error of the object, not a rule of a room. Who steps: validation [accept]s (stateless: a
 * session, a permission, the declared shape and nothing else), a room [apply]s or [reject]s (with
 * state, on its loop), the loop [emit]s (a seq), a store [persist]s. A reaction never births an
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
    fun emit(seq: Long): Event {
        check(stage == Stage.APPLIED) { "$name: $stage → emit" }
        check(meta.seq == null) { "$name: emitted twice" }
        meta.seq = seq
        return this
    }

    val emitted: Boolean get() = meta.seq != null

    fun persist(): Event = step(Stage.PERSISTED, Stage.APPLIED) { check(emitted) { "$name: persisted before emitted" } }

    private inline fun step(to: Stage, vararg from: Stage, stamp: () -> Unit): Event {
        check(stage in from) { "$name: $stage → $to" }
        stamp()
        meta.stage = to
        return this
    }

    /** The declaration as data: what a catalog serves, what intents.js validates against. */
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
        var seq by long()        // emitted, the room's order
        var reason by string()   // rejected: a key the browser localizes…
        var args by obj()        // …with these
    }

    companion object {
        /** The key a client must not use: whatever it holds is dropped at birth. */
        const val META = "_"
    }
}

/**
 * Every event a room speaks, by name: a door builds the right class from a frame, a browser
 * without Kotlin reads the catalog as JSON.
 */
class Catalog {
    private val factories = LinkedHashMap<String, (Json.MutableObject) -> Event>()

    fun <E : Event> register(factory: (Json.MutableObject) -> E): Catalog = apply {
        factories[factory(Json.MutableObject()).name] = factory
    }

    val names: Set<String> get() = factories.keys

    /** An event of that name over that JSON, or null when the room does not speak it. */
    fun of(name: String, json: Json.MutableObject = Json.MutableObject()): Event? = factories[name]?.invoke(json)

    /** A frame as received: the name is the SSE event name, the data its JSON. */
    fun of(name: String, data: String): Event? = of(name, Json.parse(data) as? Json.MutableObject ?: return null)

    fun describe(): Json.Array = Json.Array(factories.values.map { it(Json.MutableObject()).describe() })
}
