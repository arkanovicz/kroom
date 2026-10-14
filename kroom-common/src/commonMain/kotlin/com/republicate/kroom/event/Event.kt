package com.republicate.kroom.event

import com.republicate.kson.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Where an applied event goes: every connection, the room, or users the room names (the emitter at least). */
enum class Audience { GLOBAL, ROOM, USERS }

/**
 * One life, in order; the stage on the wire is the verdict, the seq the proof it went out.
 * A room-born event starts at APPLIED; a rejection is emitted to its emitter alone.
 */
enum class Stage { BORN, ACCEPTED, APPLIED, REJECTED, PERSISTED }

/** What the browser localizes: a key and its arguments, never a sentence. */
class Reason(val key: String, val args: Json.Object = Json.Object()) {
    override fun toString() = if (args.isEmpty()) key else "$key $args"
}

/**
 * The one thing that goes through E, from a gesture to a row: a name, a payload, metadata.
 * The payload is the JSON, the class is a typed view over it; the metadata lives under the
 * reserved key `_` beside the payload, so a frame is `{"_": {stage, from, seq, …}, ...payload}`
 * and every reaction keeps reading `data.x`. A subclass is a declaration — name, audience kind,
 * permission, fields — all data, what a catalog hands to a browser without Kotlin.
 *
 * The stages are the lifecycle, walked in order on this very object; a step out of order is an
 * error of the object, not a rule of a room. Who steps: a door [accept]s (stateless: a session,
 * a permission, a well-formed payload), a room [apply]s or [reject]s (with state, on its loop),
 * the loop [emit]s (a seq, the stage stays the verdict), a store [persist]s. A reaction never births an event: only a gesture, a
 * clock, or a room's apply does.
 */
abstract class Event(
    val name: String,
    val audience: Audience = Audience.ROOM,
    val permission: String? = null,
    json: Json.MutableObject = Json.MutableObject()
) : JsonView(json) {

    val meta = Meta(metaOf(json))

    val stage: Stage get() = meta.stage ?: Stage.BORN

    /** The door's half: who, from which tab, and a payload of the declared shape. */
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

    /** Numbered and on the wire, once; a rejection reaches its emitter whatever the declared audience. */
    fun emit(seq: Long): Event {
        check(stage == Stage.APPLIED || stage == Stage.REJECTED) { "$name: $stage → emit" }
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

    /** The metadata, the same view over `_`. */
    class Meta(json: Json.MutableObject) : JsonView(json) {
        var stage by enum<Stage>()
        var from by string()     // the author's id — a user's, or `discord:<snowflake>` for a bridge
        var tab by string()      // the emitting tab: the browser frees its own trigger, nobody routes on it
        var at by long()         // applied, epoch millis
        var seq by long()        // emitted, the room's order
        var reason by string()   // rejected: a key the browser localizes…
        var args by obj()        // …with these
    }

    companion object {
        const val META = "_"

        private fun metaOf(json: Json.MutableObject): Json.MutableObject =
            when (val m = json[META]) {
                is Json.MutableObject -> m
                is Map<*, *> -> Json.MutableObject(m.entries.associate { it.key.toString() to it.value }).also { json[META] = it }
                else -> Json.MutableObject().also { json[META] = it }
            }
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
