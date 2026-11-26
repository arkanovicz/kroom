package com.republicate.kroom.client

import com.republicate.kroom.event.Event
import com.republicate.kroom.event.State
import com.republicate.kson.Json
import kotlinx.browser.document
import kotlinx.browser.window
import org.apache.velocity.engine.dom.JsTree
import org.w3c.dom.Element
import org.w3c.dom.EventSource
import org.w3c.dom.Node
import kotlin.js.Promise

/** The page's language, as `<html lang>` says; English when it says nothing. */
fun pageLanguage(): String = document.documentElement?.getAttribute("lang")?.takeIf { it.isNotEmpty() } ?: "en"

/** The page merged under [root] of the live document, its `[data-kroom-intent]` triggers posting to the room — through [post], when a region has a turn of its own to keep. */
fun <S : State> Room<S>.attach(template: String, root: Element = document.body!!, post: (String, Json.Object) -> Promise<dynamic> = { type, payload -> act(type, payload) }): Room<S> {
    showBroken()
    return attach(template, JsTree(document), root as Node).also { Intents(root, store.events, post) }
}

/** What a broken region looks like where the page says nothing: `:where()` weighs nothing, any rule of the page's wins. */
internal const val BROKEN_STYLE =
    ":where([${Room.BROKEN}]) { outline: 2px dashed #dc2626; outline-offset: 2px; min-height: 1.5rem; }\n" +
    ":where([${Room.BROKEN}])::before { content: \"\u26A0\"; color: #dc2626; }"

private var brokenShown = false

/** [BROKEN_STYLE] given to the document, once: a constructed sheet where the browser has them, no element for a style policy to refuse. */
internal fun showBroken() {
    if (brokenShown) return
    brokenShown = true
    val doc: dynamic = document
    if (doc.adoptedStyleSheets != null) {
        val sheet: dynamic = js("new CSSStyleSheet()")
        sheet.replaceSync(BROKEN_STYLE)
        doc.adoptedStyleSheets = doc.adoptedStyleSheets.concat(sheet)
    } else document.head?.appendChild(document.createElement("style").also { it.textContent = BROKEN_STYLE })
}

/**
 * The room's stream (`<api>/rooms/<name>/stream`, what kroom-server's `Route.room` mounts): the snapshot first, then
 * every frame in order, each folded and merged. A reconnect is a join: [Stream.close] and connect again. The stream
 * follows the page's life: `pagehide` closes it (a page in the back/forward cache keeps its `EventSource` open, the
 * server only sees the drop when the browser discards the page — 60 s in Chrome), `pageshow` opens it again, the
 * same tab id, the snapshot first like any reconnect (back within the graces: a `status`, no `leave`, no `join`); a
 * reload's `pagehide` is the close the unload would have made. `api.js` owns the API's base and the tab id.
 */
fun Room<*>.connect(stream: String = "${api.base}rooms/${encodeURIComponent(name)}/stream"): Stream {
    val tab = api.tabId() as String?
    val url = if (tab == null) stream else stream + (if ('?' in stream) "&" else "?") + "tabId=" + encodeURIComponent(tab)
    return Stream(this, url)
}

/** The room's stream over an `EventSource`, open until [close]d; parked while the page is away (`pagehide` → `pageshow`). */
class Stream internal constructor(private val room: Room<*>, val url: String) {
    private var source: EventSource? = open()
    private var parked = false
    private val hide: (org.w3c.dom.events.Event) -> Unit = { source?.let { s -> s.close(); source = null; parked = true } }
    private val show: (org.w3c.dom.events.Event) -> Unit = { if (parked) { parked = false; source = open() } }

    init {
        window.addEventListener("pagehide", hide)
        window.addEventListener("pageshow", show)
    }

    private fun open(): EventSource = EventSource(url).also { source ->
        for (name in room.store.events.all + "state") source.addEventListener(name, { e: org.w3c.dom.events.Event -> room.frame(name, e.asDynamic().data as String) })
    }

    /** Closed for good: no frame after this, nothing reopened on `pageshow`. */
    fun close() {
        window.removeEventListener("pagehide", hide)
        window.removeEventListener("pageshow", show)
        source?.close(); source = null; parked = false
    }
}

/** Post an intent, typed: `room.act(Play(…))`, its name its path. */
fun Room<*>.act(event: Event): Promise<dynamic> = act(event.name, event.json)

/**
 * Post an intent to the room (`<api>/rooms/<name>/<type>`); the reply is the verdict (a rejected promise carries `data.reason`). What the room would
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
