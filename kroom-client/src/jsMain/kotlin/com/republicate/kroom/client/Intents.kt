package com.republicate.kroom.client

import com.republicate.kroom.event.RoomEvents
import com.republicate.kson.Json
import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLFormElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import org.w3c.dom.get
import org.w3c.xhr.FormData
import kotlin.js.Promise

/**
 * `[data-kroom-intent]` triggers: a click posts the intent and leaves the trigger busy until the reply, the
 * verdict: accepted frees it (and resets its form), rejected frees it and shows the reason right after it. The
 * payload is the trigger's `data-kroom-<field>` values, plus its form's fields when in a form, each text in its
 * declared kind (`Field.parse`: a number, a boolean, an empty field null); a `cancel` names what it withdraws with
 * `data-kroom-intent-of`. Call sites hold no code. A trigger is told from the others by its selector — its intent and
 * its `data-kroom-*` fields — so two triggers of one intent with different fields (a vote per line) run independently
 * while a second click on the same one is swallowed. Triggers inside a region may be rebuilt by a merge while their
 * POST runs: they are found again by selector when the reply comes.
 */
class Intents(private val root: Element, private val events: RoomEvents, private val act: (String, Json.Object) -> Promise<dynamic>) {
    private val pending = HashSet<String>()
    private val attribute = "data-kroom-intent"
    private val fieldPrefix = "data-kroom-"

    init {
        root.addEventListener("click", { e ->
            val el = (e.target as? Element)?.closest("[$attribute]") as? HTMLElement ?: return@addEventListener
            if (!root.contains(el)) return@addEventListener
            e.preventDefault()   // the intent replaces whatever the element would have done (submit, follow)
            val intent = el.getAttribute(attribute) ?: return@addEventListener
            val trigger = selector(el)
            if (trigger in pending) return@addEventListener
            pending += trigger
            busy(el, true)
            clearReason(trigger)
            act(intent, payload(intent, el)).then<Unit>(
                onFulfilled = { settle(trigger, null); formOf(el)?.reset() },
                onRejected = { err -> settle(trigger, reasonOf(err)) })
        })
    }

    fun payload(intent: String, el: HTMLElement): Json.Object {
        val out = Json.MutableObject()
        val intentOf = el.getAttribute("$attribute-of")
        if (el.getAttribute(attribute) == "cancel" && intentOf != null) return out.also { it["intent"] = intentOf }
        val fields = events.of(intent)?.fields.orEmpty()
        fun put(k: String, text: String) {
            val field = fields.firstOrNull { it.name == k }
            out[k] = if (field != null) field.parse(text) else text   // parsed null included; an undeclared key as is, for the shape to refuse
        }
        for (i in 0 until el.attributes.length) {
            val a = el.attributes[i] ?: continue
            if (a.name.startsWith(fieldPrefix) && !a.name.startsWith(attribute)) put(camel(a.name.removePrefix(fieldPrefix)), a.value)
        }
        val form = formOf(el)
        if (form != null) {
            val fd = FormData(form)
            val keys = js("Array.from(fd.keys())") as Array<String>
            for (k in keys) put(k, fd.get(k)?.toString() ?: "")
        }
        return out
    }

    private fun formOf(el: HTMLElement) = (el as? HTMLButtonElement)?.form ?: (el as? HTMLInputElement)?.form ?: el.closest("form") as? HTMLFormElement

    private fun camel(s: String) = s.split('-').mapIndexed { i, p -> if (i == 0) p else p.replaceFirstChar { it.uppercase() } }.joinToString("")

    private fun settle(trigger: String, reason: String?) {
        pending -= trigger
        triggers(trigger).forEach { busy(it, false) }
        if (reason != null) showReason(trigger, reason) else clearReason(trigger)
    }

    /** The trigger's selector: its intent, then each other `data-kroom-*` attribute (`-of` included: a cancel per intent). */
    private fun selector(el: HTMLElement): String {
        val fields = (0 until el.attributes.length).mapNotNull { el.attributes[it] }.filter { it.name.startsWith(fieldPrefix) && it.name != attribute }
        return "[$attribute=${quote(el.getAttribute(attribute) ?: "")}]" + fields.sortedBy { it.name }.joinToString("") { "[${it.name}=${quote(it.value)}]" }
    }

    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun triggers(trigger: String) = root.querySelectorAll(trigger).asList().map { it as HTMLElement }

    private fun busy(el: HTMLElement, on: Boolean) {
        if (on) { el.setAttribute("aria-busy", "true"); el.setAttribute("disabled", "") }
        else { el.removeAttribute("aria-busy"); el.removeAttribute("disabled") }
    }

    private fun clearReason(trigger: String) =
        root.querySelectorAll("[$attribute-reason=${quote(trigger)}]").asList().forEach { (it as Element).remove() }

    /**
     * The reason is a key the page may hold a sentence for (`#messages [data-kroom-msg="play:already_played"]`), else shown raw with its args.
     * TODO: sentences no page holds (reasons, informative messages, outputs the server decides) need one generic, localized home.
     */
    private fun showReason(trigger: String, reason: String) {
        clearReason(trigger)
        triggers(trigger).firstOrNull()?.let { el ->
            val intent = el.getAttribute(attribute)
            val small = document.createElement("small")
            small.className = "intent-reason"
            small.setAttribute("role", "alert")
            small.setAttribute("$attribute-reason", trigger)
            small.textContent = say("$intent:$reason").let { if (it == "$intent:$reason") reason else it }
            el.after(small)
        }
    }

    private fun reasonOf(err: dynamic): String {
        val data = err?.data
        val reason = (data?.reason ?: err?.message ?: "rejected").toString()
        val args = data?.args
        if (args == null) return reason
        val keys = js("Object.keys(args)") as Array<String>
        return if (keys.isEmpty()) reason else "$reason (${keys.joinToString(", ") { "$it: ${args[it]}" }})"
    }
}

/** A sentence held by the page (`#messages [data-kroom-msg=key]`, so l10n translates it), `{k}` filled from [vars]; the key when absent. */
fun say(key: String, vars: Map<String, Any?> = emptyMap()): String {
    val text = document.querySelector("#messages [data-kroom-msg=\"$key\"]")?.textContent ?: key
    return Regex("""\{(\w+)\}""").replace(text) { m -> vars[m.groupValues[1]]?.toString() ?: "" }
}
