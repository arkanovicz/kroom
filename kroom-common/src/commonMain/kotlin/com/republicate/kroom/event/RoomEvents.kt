package com.republicate.kroom.event

import com.republicate.kson.Json

/**
 * A room's alphabet, static and common: every name on its wire, each an [intent] (a client may post it) or an
 * [event] (born by the room, emitted by its logic). An app declares its own as an object, one typed property per
 * name, `object LobbyEvents : ChatEvents() { val play = intent(::Play, cancellable = true) }`; kroom's come first
 * in every room. The room's door and dispatch, the routes, the browser's fold, posts and stream, and the catalog
 * read it. A door builds the right class from a frame, a browser without Kotlin reads it as JSON.
 */
open class RoomEvents {
    /** One name: its class (the factory a frame is rebuilt with), whether a client may post it, whether `cancel` may withdraw its deposit. */
    class Entry<E : Event> internal constructor(
        val name: String,
        val factory: (Json.MutableObject) -> E,
        val postable: Boolean,
        val cancellable: Boolean
    )

    private val entries = LinkedHashMap<String, Entry<*>>()

    // kroom's: join and leave born by the room, status and cancel posted by any client
    val join = event(::Join)
    val leave = event(::Leave)
    val status = intent(::Status)
    val cancel = intent(::Cancel)

    /** A name a client may post; [cancellable]: `cancel` may withdraw its deposit. */
    protected fun <E : Event> intent(factory: (Json.MutableObject) -> E, cancellable: Boolean = false): Entry<E> = add(factory, true, cancellable)

    /** A name nobody posts: born by the room, emitted by its logic. */
    protected fun <E : Event> event(factory: (Json.MutableObject) -> E): Entry<E> = add(factory, false, false)

    /** The `on()` path's: a room declaring its handlers one by one registers their classes as it goes; a name already there stays. */
    @Suppress("UNCHECKED_CAST")
    fun <E : Event> register(factory: (Json.MutableObject) -> E): Entry<E> =
        entries[factory(Json.MutableObject()).name] as Entry<E>? ?: add(factory, true, false)

    private fun <E : Event> add(factory: (Json.MutableObject) -> E, postable: Boolean, cancellable: Boolean): Entry<E> {
        val name = factory(Json.MutableObject()).name
        require(name !in entries) { "'$name' declared twice" }
        return Entry(name, factory, postable, cancellable).also { entries[name] = it }
    }

    /** The entry of that name, null when the room does not speak it. */
    fun entry(name: String): Entry<*>? = entries[name]

    /** The postable names. */
    val names: Set<String> get() = entries.values.filter { it.postable }.map { it.name }.toSet()

    /** Every name on the wire. */
    val all: Set<String> get() = entries.keys

    /** The intent of that name over that JSON, or null when nobody may post it. */
    fun of(name: String, json: Json.MutableObject = Json.MutableObject()): Event? = entries[name]?.takeIf { it.postable }?.factory?.invoke(json)

    /** A frame as received: the name is the SSE event name, the data its JSON. */
    fun of(name: String, data: String): Event? = of(name, Json.parse(data) as? Json.MutableObject ?: return null)

    /** Any name's class, for a fold: a frame is rebuilt whether posted or emitted. */
    fun frame(name: String, json: Json.MutableObject): Event? = entries[name]?.factory?.invoke(json)

    fun describe(): Json.Array = Json.Array(entries.values.map { e ->
        Json.MutableObject(e.factory(Json.MutableObject()).describe()).also { it["postable"] = e.postable; it["cancellable"] = e.cancellable }
    })
}
