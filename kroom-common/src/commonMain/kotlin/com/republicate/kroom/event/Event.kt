package com.republicate.kroom.event

import com.republicate.kson.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Where an applied event goes when nothing names its users: the room, or the emitter. Legacy of the `on()` path: `to` says it now. */
enum class Audience { ROOM, USERS }

/**
 * One life, in order. A room-born event starts at APPLIED; a rejection is the verdict on the
 * reply and goes no further.
 */
enum class Stage { BORN, ACCEPTED, APPLIED, REJECTED, PERSISTED }

/** What the browser localizes: a key and its arguments, never a sentence. As an [Outcome], the transition does not fire. */
class Reason(val key: String, val args: Json.Object = Json.Object()) : Outcome<Nothing> {
    override fun toString() = if (args.isEmpty()) key else "$key $args"
}

/** The names a transition may fire: its arrow in the grammar, checked when it fires, served by the registry. */
class Fires(internal val factories: List<(Json.MutableObject) -> Event>) {
    constructor(vararg factories: (Json.MutableObject) -> Event) : this(factories.toList())
    val names: Set<String> by lazy { factories.map { it(Json.MutableObject()).name }.toSet() }
    companion object { val none = Fires() }
}

/**
 * The one thing that goes through E, from a gesture to a row: a name, a payload, metadata.
 * The payload is the JSON, the class is a typed view over it, and the payload alone is the wire:
 * a frame is `{...payload}`, its position on the room's channel is the SSE id. The metadata
 * (who, when, the stage, a reason) is the object's, server-side; a `_` key a client sends is
 * dropped at birth. A subclass is a declaration — name, fields, what it [fires], whether it may
 * stay [pending] — all data, what a registry hands to a browser without Kotlin.
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
    val fires: Fires = Fires.none,
    val pending: Boolean = false,
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

    /** The users this frame is for; none means the room. */
    val to: List<String> get() = meta.to?.map { it as String } ?: emptyList()

    /** The payload a viewer gets: the author's tabs see it whole, the others without the `mine` fields. */
    fun payload(author: Boolean): Json.Object =
        if (author || mine.isEmpty()) json else Json.MutableObject(json).also { j -> mine.forEach { j.remove(it) } }

    /** The declaration as data: what a registry serves, what a browser without Kotlin reads. */
    fun describe(): Json.Object = Json.Object(
        "name" to name,
        "audience" to audience.name,
        "permission" to permission,
        "pending" to pending,
        "fires" to Json.Array(fires.names.toList()),
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
        var to by array()        // the users this frame is for; none means the room
        var held by bool()       // applied and kept: the intent is pending
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

/**
 * The alphabet of a room, as declared: the intents (what a door may bring, by name) and, through
 * what they fire, every other name on the wire; the presence hooks' arrows beside. A door builds
 * the right class from a frame, a browser without Kotlin reads it as JSON.
 */
class Registry(
    intents: List<(Json.MutableObject) -> Event> = emptyList(),
    val join: Fires = Fires.none,
    val leave: Fires = Fires.none,
    val status: Fires = Fires.none
) {
    constructor(vararg intents: (Json.MutableObject) -> Event) : this(intents.toList())

    private val factories = LinkedHashMap<String, (Json.MutableObject) -> Event>()
    private val postable = LinkedHashSet<String>()

    // the room's own first: join and leave on every wire, posted by nobody; status and cancel, kroom's intents, posted by any client
    init { fired(::Join); fired(::Leave); register(::Status); register(::Cancel); intents.forEach { register(it) } }

    /** An intent: a name a door may bring. What it fires is registered with it, as fired only. */
    fun <E : Event> register(factory: (Json.MutableObject) -> E): Registry = apply {
        val sample = factory(Json.MutableObject())
        factories[sample.name] = factory
        postable += sample.name
        sample.fires.factories.forEach { fired(it) }
    }

    /** A name on the wire that nobody posts: fired by a transition, by presence, by a clock. */
    fun fired(factory: (Json.MutableObject) -> Event): Registry = apply {
        val sample = factory(Json.MutableObject())
        if (sample.name !in factories) { factories[sample.name] = factory; sample.fires.factories.forEach { fired(it) } }
    }

    /** The postable names. */
    val names: Set<String> get() = postable

    /** Every name on the wire. */
    val all: Set<String> get() = factories.keys

    /** The intent of that name over that JSON, or null when nobody may post it. */
    fun of(name: String, json: Json.MutableObject = Json.MutableObject()): Event? = if (name in postable) factories[name]?.invoke(json) else null

    /** A frame as received: the name is the SSE event name, the data its JSON. */
    fun of(name: String, data: String): Event? = of(name, Json.parse(data) as? Json.MutableObject ?: return null)

    /** Any name's class, for a fold: a frame is rebuilt whether posted or fired. */
    fun frame(name: String, json: Json.MutableObject): Event? = factories[name]?.invoke(json)

    fun factory(name: String): ((Json.MutableObject) -> Event)? = factories[name]

    fun describe(): Json.Array = Json.Array(factories.map { (name, f) ->
        Json.MutableObject(f(Json.MutableObject()).describe()).also { it["postable"] = name in postable }
    })
}
