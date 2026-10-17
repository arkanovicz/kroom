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

/**
 * `[data-intent]` triggers: a click posts the intent and leaves the trigger busy until the reply,
 * the verdict: accepted frees it, rejected frees it and shows the reason right after it. The
 * payload is the trigger's dataset (`data-move="rock"`), plus its form's fields when in a form; a
 * `cancel` names what it withdraws with `data-intent-of`. Call sites hold no code.
 */
class Intents internal constructor(private val client: Client<*>) {
    private val pending = HashSet<String>()

    internal fun attach() {
        client.root.addEventListener("click", { e ->
            val el = (e.target as? Element)?.closest("[data-intent]") as? HTMLElement ?: return@addEventListener
            if (!client.root.contains(el)) return@addEventListener
            e.preventDefault()   // the intent replaces whatever the element would have done (submit, follow)
            val intent = el.getAttribute("data-intent") ?: return@addEventListener
            if (intent in pending) return@addEventListener
            pending += intent
            busy(el, true)
            clearReason(intent)
            client.act(intent, payload(el)).then<Unit>(
                onFulfilled = { settle(intent, null); formOf(el)?.reset() },
                onRejected = { err -> settle(intent, reasonOf(err)) })
        })
    }

    fun payload(el: HTMLElement): Json.Object {
        val out = Json.MutableObject()
        val intentOf = el.getAttribute("data-intent-of")
        if (el.getAttribute("data-intent") == "cancel" && intentOf != null) return out.also { it["intent"] = intentOf }
        for (i in 0 until el.attributes.length) {
            val a = el.attributes[i] ?: continue
            if (a.name.startsWith("data-") && !a.name.startsWith("data-intent") && !a.name.startsWith("data-k-")) out[camel(a.name.removePrefix("data-"))] = a.value
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

    private fun triggers(intent: String) = client.root.querySelectorAll("[data-intent=\"$intent\"]").asList().map { it as HTMLElement }

    private fun busy(el: HTMLElement, on: Boolean) {
        if (on) { el.setAttribute("aria-busy", "true"); el.setAttribute("disabled", "") }
        else { el.removeAttribute("aria-busy"); el.removeAttribute("disabled") }
    }

    private fun clearReason(intent: String) =
        client.root.querySelectorAll("[data-intent-reason=\"$intent\"]").asList().forEach { (it as Element).remove() }

    /** The reason is a key the page may hold a sentence for (`#messages [data-msg="play:already_played"]`), else shown raw with its args. */
    private fun showReason(intent: String, reason: String) {
        clearReason(intent)
        triggers(intent).firstOrNull()?.let { el ->
            val small = document.createElement("small")
            small.className = "intent-reason"
            small.setAttribute("role", "alert")
            small.setAttribute("data-intent-reason", intent)
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
