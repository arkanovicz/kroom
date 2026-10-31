package com.republicate.kroom.client

import com.republicate.kroom.event.State
import com.republicate.kson.Json
import kotlinx.browser.document
import org.apache.velocity.engine.dom.JsTree
import org.w3c.dom.Element
import org.w3c.dom.EventSource
import org.w3c.dom.Node
import kotlin.js.Promise

/** The page merged under [root] of the live document, its `[data-kroom-intent]` triggers posting to the room. */
fun <S : State> Room<S>.attach(template: String, root: Element = document.body!!): Room<S> =
    attach(template, JsTree(document), root as Node).also { Intents(root) { type, payload -> act(type, payload) } }

/**
 * The room's stream: the snapshot first, then every frame in order, each folded and merged. A reconnect is a
 * join: close the source and connect again. `api.js` owns the tab id.
 */
fun Room<*>.connect(events: String = "/events"): EventSource {
    val tab = api.tabId() as String?
    val url = if (tab == null) events else events + (if ('?' in events) "&" else "?") + "tabId=" + encodeURIComponent(tab)
    val source = EventSource(url)
    for (name in store.events.all + "state") source.addEventListener(name, { e: org.w3c.dom.events.Event -> frame(name, e.asDynamic().data as String) })
    return source
}

/**
 * Post an intent to the room; the reply is the verdict (a rejected promise carries `data.reason`). What the room would
 * refuse first, its check on this view, is refused here the same way, without a POST.
 */
fun Room<*>.act(type: String, payload: Json.Object = Json.Object()): Promise<dynamic> {
    check(type, payload, api.tabId() as String?)?.let { reason ->
        val err: dynamic = Error(reason.key)
        err.data = JSON.parse(Json.Object("reason" to reason.key, "args" to reason.args).toString())
        return Promise.reject(err.unsafeCast<Throwable>())
    }
    return api.act(name, type, JSON.parse(payload.toString())).unsafeCast<Promise<dynamic>>()   // a promise of another realm (a harness) is one too
}

/** `api.js`, the stock script: the one owner of the tab id and of the POST. */
internal val api: dynamic get() = js("api")

private fun encodeURIComponent(s: String): String = js("encodeURIComponent")(s) as String
