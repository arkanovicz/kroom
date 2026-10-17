package com.republicate.kroom.client

import com.republicate.kson.Json
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTemplateElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.asList

/**
 * The page as a function of the JSON: after every frame, each bound element under [root] is
 * projected again, written only when it changed. An `each` owns its subtree (paths relative to
 * the item), an `on` owns its (paths against the frame, see [flash]); the root pass skips both.
 */
internal object Projector {
    private const val BOUND = "[data-k-field],[data-k-show],[data-k-each],[data-k-follow]"
    private val shows = HashMap<String, Show>()
    private val paths = HashMap<String, Path>()

    private fun show(text: String) = shows.getOrPut(text) { Show.parse(text) }
    private fun path(text: String) = paths.getOrPut(text) { Path.parse(text) }

    /** Project [root] and its bound descendants against [json]; [owner] is the subtree's owner (an `each` item, an `on`), the root itself when none. */
    fun project(root: Element, json: Json.Object?) {
        val targets = listOf(root).filter { it.matches(BOUND) } + root.querySelectorAll(BOUND).asList().map { it as Element }
        for (el in targets) {
            if (el !== root && owned(el, root)) continue
            el.getAttribute(Attr.FIELD.attr)?.let { field(el, path(it).resolve(json)) }
            el.getAttribute(Attr.SHOW.attr)?.let { s ->
                val on = show(s).test(json)
                if (el.hasAttribute("hidden") == on) { if (on) el.removeAttribute("hidden") else el.setAttribute("hidden", "") }
                if (on && el.hasAttribute(Attr.FOLLOW.attr) && !el.hasAttribute("data-k-followed")) {
                    el.setAttribute("data-k-followed", "")
                    (el as? HTMLAnchorElement)?.let { window.location.href = it.href }
                }
            }
            el.getAttribute(Attr.EACH.attr)?.let { each(el, path(it).resolve(json)) }
        }
    }

    /** An element under another `each` or `on` than [root] belongs to that one's pass. */
    private fun owned(el: Element, root: Element): Boolean {
        val owner = el.parentElement?.closest("[data-k-each],[data-k-on]") ?: return false
        return owner !== root && root.contains(owner)
    }

    private fun field(el: Element, value: Any?) {
        val text = when (value) { null -> ""; else -> value.toString() }
        when (el) {
            is HTMLInputElement -> if (el.value != text) el.value = text
            is HTMLTextAreaElement -> if (el.value != text) el.value = text
            is HTMLSelectElement -> if (el.value != text) el.value = text
            else -> if (el.textContent != text) el.textContent = text
        }
    }

    /** The template child cloned per item, in order; the previous items replaced. */
    private fun each(el: Element, value: Any?) {
        val template = el.querySelector("template") as? HTMLTemplateElement ?: return
        val items = (value as? Json.Array) ?: Json.Array()
        el.children.asList().filter { it !== template }.forEach { it.remove() }
        for (item in items) {
            val clone = template.content.cloneNode(true)
            el.appendChild(clone)
        }
        // the clones are in place: project each against its item (the template's content may hold one root or many)
        val rendered = el.children.asList().filter { it !== template }
        var i = 0
        val perItem = if (items.isEmpty()) 0 else rendered.size / items.size
        for (item in items) {
            val itemJson = item as? Json.Object
            repeat(perItem) { rendered.getOrNull(i++)?.let { project(it, itemJson) } }
        }
    }
}
