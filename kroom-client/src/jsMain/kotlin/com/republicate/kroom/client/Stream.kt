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
    showMarks()
    return attach(template, JsTree(document), root as Node).also { Intents(root, store.events, post) }
}

/** What a broken region, or one offline, looks like where the page says nothing: `:where()` weighs nothing, any rule of the page's wins. */
internal const val MARKS_STYLE =
    ":where([${Room.BROKEN}]) { outline: 2px dashed #dc2626; outline-offset: 2px; min-height: 1.5rem; }\n" +
    ":where([${Room.BROKEN}])::before { content: \"\u26A0\"; color: #dc2626; }\n" +
    ":where([${Room.OFFLINE}]) { opacity: .5; filter: grayscale(1); cursor: progress; }"

private var marksShown = false

/** [MARKS_STYLE] given to the document, once: a constructed sheet where the browser has them, no element for a style policy to refuse. */
internal fun showMarks() {
    if (marksShown) return
    marksShown = true
    val doc: dynamic = document
    if (doc.adoptedStyleSheets != null) {
        val sheet: dynamic = js("new CSSStyleSheet()")
        sheet.replaceSync(MARKS_STYLE)
        doc.adoptedStyleSheets = doc.adoptedStyleSheets.concat(sheet)
    } else document.head?.appendChild(document.createElement("style").also { it.textContent = MARKS_STYLE })
}

/**
 * The room's stream (`<api>/rooms/<name>/stream`, what kroom-server's `Route.room` mounts): the snapshot first, then
 * every frame in order, each folded and merged. A reconnect is a join: [Stream.close] and connect again. The stream
 * follows the page's life: `pagehide` closes it (a page in the back/forward cache keeps its `EventSource` open, the
 * server only sees the drop when the browser discards the page — 60 s in Chrome), `pageshow` opens it again, the
 * same tab id, the snapshot first like any reconnect (back within the graces: a `status`, no `leave`, no `join`); a
 * reload's `pagehide` is the close the unload would have made. `api.js` owns the API's base and the tab id.
 *
 * A stream that fails puts the room [offline][Room.offline] until the next snapshot. The browser retries a dropped
 * connection by itself; one closed by an HTTP error (a 503 while the server restarts) is opened again here, after 1 s,
 * then twice as long each time, up to 30 s — not while the page is away.
 */
fun Room<*>.connect(stream: String = "${api.base}rooms/${encodeURIComponent(name)}/stream"): Stream {
    val tab = api.tabId() as String?
    val url = if (tab == null) stream else stream + (if ('?' in stream) "&" else "?") + "tabId=" + encodeURIComponent(tab)
    return Stream(this, url)
}

/** The room's stream over an `EventSource`, open until [close]d; parked while the page is away (`pagehide` → `pageshow`). */
class Stream internal constructor(private val room: Room<*>, val url: String) {
    private var source: EventSource? = null
    private var parked = false
    private var retry: Int? = null   // the reopening's timer
    private var wait = 0             // the next reopening's wait, ms
    private val hide: (org.w3c.dom.events.Event) -> Unit = {
        if (source != null || retry != null) { source?.close(); source = null; cancel(); parked = true }
    }
    private val show: (org.w3c.dom.events.Event) -> Unit = { if (parked) { parked = false; source = open() } }

    init {
        source = open()
        window.addEventListener("pagehide", hide)
        window.addEventListener("pageshow", show)
    }

    private fun open(): EventSource = EventSource(url).also { source ->
        // the snapshot puts the room back online before it is folded: what reacts to it may act
        source.addEventListener("state", { _: org.w3c.dom.events.Event -> room.offline = false; wait = 0 })
        for (name in room.store.events.all + "state") source.addEventListener(name, { e: org.w3c.dom.events.Event -> room.frame(name, e.asDynamic().data as String) })
        source.addEventListener("error", { _: org.w3c.dom.events.Event ->
            if (source !== this.source) return@addEventListener
            room.offline = true
            if (source.readyState == EventSource.CLOSED) {   // an HTTP error: the browser gave up
                this.source = null
                wait = if (wait == 0) FIRST else minOf(wait * 2, LAST)
                retry = window.setTimeout({ retry = null; this.source = open() }, wait)
            }
        })
    }

    private fun cancel() { retry?.let { window.clearTimeout(it) }; retry = null }

    /** Closed for good: no frame after this, nothing reopened on `pageshow` nor after a failure. */
    fun close() {
        window.removeEventListener("pagehide", hide)
        window.removeEventListener("pageshow", show)
        source?.close(); source = null; parked = false; cancel()
    }

    private companion object {
        const val FIRST = 1000
        const val LAST = 30_000
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
