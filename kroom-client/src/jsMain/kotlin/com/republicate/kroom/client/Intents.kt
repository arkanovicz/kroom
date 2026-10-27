package com.republicate.kroom.client

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
 * payload is the trigger's `data-kroom-<field>` values, plus its form's fields when in a form; a `cancel` names
 * what it withdraws with `data-kroom-intent-of`. Call sites hold no code. Triggers inside a region may be rebuilt
 * by a merge while their POST runs: they are found again by name when the reply comes.
 */
class Intents(private val root: Element, private val act: (String, Json.Object) -> Promise<dynamic>) {
    private val pending = HashSet<String>()
    private val attribute = "data-kroom-intent"
    private val fieldPrefix = "data-kroom-"

    init {
        root.addEventListener("click", { e ->
            val el = (e.target as? Element)?.closest("[$attribute]") as? HTMLElement ?: return@addEventListener
            if (!root.contains(el)) return@addEventListener
            e.preventDefault()   // the intent replaces whatever the element would have done (submit, follow)
            val intent = el.getAttribute(attribute) ?: return@addEventListener
            if (intent in pending) return@addEventListener
            pending += intent
            busy(el, true)
            clearReason(intent)
            act(intent, payload(el)).then<Unit>(
                onFulfilled = { settle(intent, null); formOf(el)?.reset() },
                onRejected = { err -> settle(intent, reasonOf(err)) })
        })
    }

    fun payload(el: HTMLElement): Json.Object {
        val out = Json.MutableObject()
        val intentOf = el.getAttribute("$attribute-of")
        if (el.getAttribute(attribute) == "cancel" && intentOf != null) return out.also { it["intent"] = intentOf }
        for (i in 0 until el.attributes.length) {
            val a = el.attributes[i] ?: continue
            if (a.name.startsWith(fieldPrefix) && !a.name.startsWith(attribute)) out[camel(a.name.removePrefix(fieldPrefix))] = a.value
        }
        val form = formOf(el)
        if (form != null) {
            val fd = FormData(form)
            val keys = js("Array.from(fd.keys())") as Array<String>
            for (k in keys) out[k] = fd.get(k)?.toString()
        }
        return out
    }

    private fun formOf(el: HTMLElement) = (el as? HTMLButtonElement)?.form ?: (el as? HTMLInputElement)?.form ?: el.closest("form") as? HTMLFormElement

    private fun camel(s: String) = s.split('-').mapIndexed { i, p -> if (i == 0) p else p.replaceFirstChar { it.uppercase() } }.joinToString("")

    private fun settle(intent: String, reason: String?) {
        pending -= intent
        triggers(intent).forEach { busy(it, false) }
        if (reason != null) showReason(intent, reason) else clearReason(intent)
    }

    private fun triggers(intent: String) = root.querySelectorAll("[$attribute=\"$intent\"]").asList().map { it as HTMLElement }

    private fun busy(el: HTMLElement, on: Boolean) {
        if (on) { el.setAttribute("aria-busy", "true"); el.setAttribute("disabled", "") }
        else { el.removeAttribute("aria-busy"); el.removeAttribute("disabled") }
    }

    private fun clearReason(intent: String) =
        root.querySelectorAll("[$attribute-reason=\"$intent\"]").asList().forEach { (it as Element).remove() }

    /**
     * The reason is a key the page may hold a sentence for (`#messages [data-kroom-msg="play:already_played"]`), else shown raw with its args.
     * TODO: sentences no page holds (reasons, informative messages, outputs the server decides) need one generic, localized home.
     */
    private fun showReason(intent: String, reason: String) {
        clearReason(intent)
        triggers(intent).firstOrNull()?.let { el ->
            val small = document.createElement("small")
            small.className = "intent-reason"
            small.setAttribute("role", "alert")
            small.setAttribute("$attribute-reason", intent)
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
