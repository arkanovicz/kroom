@file:OptIn(ExperimentalJsExport::class)
package com.republicate.kroom.client

import com.republicate.kson.Json
import org.w3c.dom.EventSource

/**
 * What a plain-JS page may call on the attached room: `kroom.connect()`, `kroom.on('state', fn)`,
 * `kroom.act('status', {status})`, `kroom.say('joined', {name})`. The Kotlin side (an app's `main`) builds the
 * [Room] and hands it over.
 */
@JsExport
object kroom {
    private var room: Room<*>? = null
    private var source: EventSource? = null

    fun attach(r: Any) { room = r as Room<*> }
    val me: String? get() = room?.me?.id
    /** A reaction to the frames of that name, handed the event's JSON (the snapshot's for `state`). */
    fun on(name: String, fn: (dynamic) -> Unit) {
        val r = room ?: return
        r.react { n, e -> if (n == name) fn(JSON.parse((e?.json ?: r.state.json).toString())) }
    }
    fun act(type: String, payload: dynamic = js("({})")) = room?.act(type, Json.parse(JSON.stringify(payload)) as Json.Object)
    /** Open the stream, closing a previous one: a reconnect is a join. */
    fun connect() { source?.close(); source = room?.connect() }
    fun frame(name: String, data: String) { room?.frame(name, data) }
    fun say(key: String, vars: dynamic = js("({})")): String {
        val keys = js("Object.keys(vars)") as Array<String>
        return say(key, keys.associateWith { vars[it] as Any? })
    }
}
