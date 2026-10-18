package com.republicate.kroom.client

import com.republicate.kroom.event.Event
import com.republicate.kroom.event.Registry
import com.republicate.kroom.event.State
import com.republicate.kroom.event.Store
import com.republicate.kson.Json
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.EventSource
import org.w3c.dom.asList
import kotlin.js.Promise

/**
 * A page on a room: the stream folded by [Store] through the app's [State], the markup under
 * [root] projected after every frame, the `[data-intent]` triggers posting to the room. Reactions
 * (`on`) are effects after a frame, never emissions; they run before `derive`, so what a page
 * remembers from a frame (a match the marking dropped, a note) can feed the keys it derives.
 *
 * `api.js` stays the one owner of the tab id and of the POST: this client calls it.
 */
class Client<S : State>(
    val state: S,
    val registry: Registry,
    val room: String,
    val root: Element = document.body!!,
    private val events: String = "/events"
) {
    val store = Store(state, registry)
    val json: Json.MutableObject get() = store.json
    val me: String? get() = Path.me(json)

    private val reactions = HashMap<String, MutableList<(Event) -> Unit>>()
    private val derives = mutableListOf<(S, String?) -> Unit>()
    private val onState = mutableListOf<(S) -> Unit>()
    private val flashing = HashMap<Element, Int>()
    private var source: EventSource? = null
    val intents = Intents(this)

    init {
        root.querySelectorAll("[${Attr.ON.attr}]").asList().forEach { (it as Element).setAttribute("hidden", "") }
        intents.attach()
    }

    /** An effect after a frame of that name (the frame typed by the registry). */
    fun on(name: String, fn: (Event) -> Unit) = apply { reactions.getOrPut(name) { mutableListOf() } += fn }

    /** An effect after the snapshot (a reconnect lands here too). */
    fun onState(fn: (S) -> Unit) = apply { onState += fn }

    /** Computed keys, written into the JSON before each projection (`match` = the viewer's match). */
    fun derive(fn: (S, String?) -> Unit) = apply { derives += fn }

    /** Open the stream (closing a previous one): the snapshot comes first, then every frame in order. */
    fun connect() = apply {
        source?.close()
        val tab = api.tabId() as String?
        val url = if (tab == null) events else events + (if (events.contains('?')) "&" else "?") + "tabId=" + encodeURIComponent(tab)
        val es = EventSource(url)
        // a plain object with `data` will do: the harness fakes the source
        for (name in registry.all + "state") es.addEventListener(name, { e: org.w3c.dom.events.Event -> frame(name, e.asDynamic().data as String) })
        source = es
    }

    fun close() { source?.close(); source = null }

    /** One frame: fold, react, derive, flash, project. Also the harness's way in. */
    fun frame(name: String, data: String) {
        val event = store.fold(name, data)
        if (event != null) reactions[name]?.forEach { it(event) }
        else if (name == "state") onState.forEach { it(state) }
        refresh()
        if (event != null) flash(event)
    }

    /** Derive and project again, no frame: something the page alone holds changed (a timer ran out). */
    fun refresh() {
        derives.forEach { it(state, me) }
        project()
    }

    fun project() = Projector.project(root, json)

    /** Elements `on` this frame's name show for `for` ms, projected against the frame. */
    private fun flash(event: Event) {
        root.querySelectorAll("[${Attr.ON.attr}=\"${event.name}\"]").asList().forEach { el ->
            el as Element
            Projector.project(el, event.json)
            el.removeAttribute("hidden")
            flashing.remove(el)?.let { window.clearTimeout(it) }
            val span = el.getAttribute(Attr.FOR.attr)?.toIntOrNull() ?: 2000
            flashing[el] = window.setTimeout({ el.setAttribute("hidden", ""); flashing.remove(el) }, span)
        }
    }

    /** Post an intent to the room; the reply is the verdict (a rejected promise carries `data.reason`). */
    fun act(type: String, payload: Json.Object = Json.Object()): Promise<dynamic> =
        api.act(room, type, JSON.parse(payload.toString())).unsafeCast<Promise<dynamic>>()   // a promise of another realm (a harness) is one too

    companion object {
        internal val api: dynamic get() = js("api")
        private fun encodeURIComponent(s: String): String = js("encodeURIComponent")(s) as String
    }
}

/** A sentence held by the page (`#messages [data-msg=key]`, so l10n translates it), `{k}` filled from [vars]; the key when absent. */
fun say(key: String, vars: Map<String, Any?> = emptyMap()): String {
    val text = document.querySelector("#messages [data-msg=\"$key\"]")?.textContent ?: key
    return Regex("""\{(\w+)\}""").replace(text) { m -> vars[m.groupValues[1]]?.toString() ?: "" }
}
