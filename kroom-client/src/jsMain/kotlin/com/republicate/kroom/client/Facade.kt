@file:OptIn(ExperimentalJsExport::class)
package com.republicate.kroom.client

import com.republicate.kroom.event.Event

/**
 * What a plain-JS page may call on the attached client: `kroom.on('round', fn)`, `kroom.act('match')`,
 * `kroom.say('joined', {name})`. The Kotlin side (an app's `main`) builds the [Client] and hands it over.
 */
@JsExport
object kroom {
    private var client: Client<*>? = null

    fun attach(c: Any) { client = c as Client<*> }
    val me: String? get() = client?.me
    fun on(name: String, fn: (dynamic) -> Unit) { client?.on(name) { e: Event -> fn(JSON.parse(e.json.toString())) } }
    fun act(type: String, payload: dynamic = js("({})")) = client?.act(type, com.republicate.kson.Json.parse(JSON.stringify(payload)) as com.republicate.kson.Json.Object)
    fun connect() { client?.connect() }
    fun frame(name: String, data: String) { client?.frame(name, data) }
    fun say(key: String, vars: dynamic = js("({})")): String {
        val keys = js("Object.keys(vars)") as Array<String>
        return say(key, keys.associateWith { vars[it] as Any? })
    }
}
