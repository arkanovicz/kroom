@file:OptIn(ExperimentalJsExport::class)
package com.republicate.kroom.spike

import com.republicate.kroom.event.Chat
import com.republicate.kroom.event.Join
import com.republicate.kroom.event.Leave
import com.republicate.kroom.event.Status
import com.republicate.kson.Json
import kotlinx.browser.window
import org.w3c.dom.Element

/** The harness's handle: the page mounted under [root], frames in as JSON text. */
@JsExport
class Spike(root: Element) {
    private val page = ChatPage(root)
    private val state = ChatState(page.storage)

    fun snapshot(json: String) { state.snapshot(parse(json)) }

    fun frame(name: String, json: String) {
        val obj = parse(json)
        state.reduce(when (name) {
            "join" -> Join(obj)
            "leave" -> Leave(obj)
            "status" -> Status(obj)
            "chat" -> Chat(obj)
            else -> return
        })
    }

    private fun parse(json: String) = Json.parse(json).let { it as? Json.MutableObject ?: Json.MutableObject(it as Json.Object) }
}

fun main() {
    window.asDynamic().spike = { root: Element -> Spike(root) }
}
