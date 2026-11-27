package com.republicate.kroom.markdown.editor

import com.republicate.kroom.client.Room
import com.republicate.kroom.client.act
import com.republicate.kroom.client.attach
import com.republicate.kroom.event.Part
import com.republicate.kroom.event.TextState
import com.republicate.kson.Json
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement
import kotlin.js.Promise

/**
 * The part this tab holds, as it is typed in. The region shows that part as a `textarea[data-kroom-part]` (the
 * part's id) once the `take` is folded; from there this posts what the text says (`write`, the caret with it), a
 * `take {after}` when a paragraph is begun (a line left blank, then more: the part held is left to what came
 * before, the rest goes on in the part born), and a `release` when the tab leaves the textarea or stays [idle] ms
 * without touching it.
 *
 * One tab says one thing at a time: a text's intents leave in the order they were made, each after the reply to
 * the one before, and a `write` says the text as it is when it leaves — the region's own triggers (a click that
 * takes another part) go through [post] for the same reason. What is typed while a part is on its way goes into
 * its textarea when it comes; what is typed in a part no longer held is dropped.
 *
 * The keyboard moves it between parts ([move]): `write` registers the keys with kroom-webapp-assets' keys.js.
 */
class Writer(
    private val root: Element,
    private val act: (String, Json.Object) -> Promise<dynamic>,
    private val idle: Int = 5000,
    private val cut: (String) -> List<String> = Part::cut,
) {

    private class Step(val type: String, val payload: () -> Json.Object?, val done: (dynamic) -> Unit = {}, val failed: (dynamic) -> Unit = {})

    private val steps = ArrayDeque<Step>()
    private var busy = false

    private var area: HTMLTextAreaElement? = null   // the textarea of the part held
    private var part: String? = null                // its id
    private var said: String? = null                // what the room was last told of it
    private var begun: Int? = null                  // a paragraph was begun: where, in the textarea, what goes on in it starts
    private var leaving = false                     // its release is asked
    private var timer: Int? = null
    private var landing: Boolean? = null            // a part reached by a key: its caret at its start (true) or its end, once taken

    init {
        root.addEventListener("input", { e ->
            held(e.target)?.let { area ->
                arm()
                if (leaving || begun != null) return@let
                if (e.asDynamic().inputType == "insertLineBreak" && begins(area)) begin(area) else tell()
            }
        })
        for (type in listOf("keyup", "mouseup")) root.addEventListener(type, { e -> held(e.target)?.let { arm(); if (!leaving && begun == null) tell() } })
        // a part someone else holds: no take to ask, the click focuses it and it glows, as a key does
        root.addEventListener("click", { e ->
            ((e.target as? Element)?.closest("[data-holder]") as? HTMLElement)?.takeIf { it.parentElement === root }?.let(::glow)
        })
        // once the event settled: a focus gone elsewhere, not a textarea being rebuilt
        root.addEventListener("focusout", { e -> held(e.target)?.let { area -> window.setTimeout({ if (area === this.area && document.activeElement !== area) leave() }, 0) } })
    }

    /** After a merge: the textarea of a part just taken is given the focus and what was typed while it was on its way. */
    fun merged() {
        val now = root.querySelector("textarea[data-kroom-part]") as? HTMLTextAreaElement
        val id = now?.getAttribute("data-kroom-part")
        if (now == null || id == null) return forget()   // given back, or lost: what was not said is dropped
        if (now === area && id == part) return
        val rest = begun?.let { area?.value?.drop(it)?.trimStart('\n') }
        forget()
        area = now
        part = id
        if (!rest.isNullOrEmpty()) now.value = rest
        now.focus()
        val caret = if (landing == true) 0 else now.value.length
        landing = null
        now.setSelectionRange(caret, caret)
        arm()
        tell()
    }

    /** An intent of this text's, in its turn: after what this tab already said, the text as typed first. */
    fun post(type: String, payload: Json.Object): Promise<dynamic> = Promise { resolve, reject ->
        steps += Step("write", ::text)
        steps += Step(type, { payload }, resolve, { reject(it.unsafeCast<Throwable>()) })
        run()
    }

    /**
     * A key moving the writer to the part before ([up]) or after, by a line or a [page]. In the part held, a line
     * moves from its first (last) line as laid out, a page puts the caret at its start (end) first; from a part
     * shown, either moves on. The part reached is taken, the caret at its end going up, at its start going down;
     * the take refused (held, or the room's check), that part gets the focus and this tab writes no more. False
     * when the key is not the writer's: the caret has a line to go, or there is no part there.
     */
    fun move(up: Boolean, page: Boolean): Boolean {
        val at = document.activeElement ?: return false
        val from = at.closest(PART)?.takeIf { it.parentElement === root } ?: return false
        if (at === area) {
            val area = at as HTMLTextAreaElement
            val edge = if (up) 0 else area.value.length
            if (page) {
                if (area.selectionStart != edge || area.selectionEnd != edge) { area.setSelectionRange(edge, edge); return true }
            } else if (area.selectionStart != area.selectionEnd || !onEdge(area, up)) return false
        }
        var to = if (up) from.previousElementSibling else from.nextElementSibling
        while (to != null && !to.matches(PART)) to = if (up) to.previousElementSibling else to.nextElementSibling
        val id = to?.getAttribute("data-kroom-ids") ?: return false
        landing = !up
        post("take", Json.Object("ids" to Json.Array(id))).catch<Unit> {
            landing = null
            (root.querySelector("[data-kroom-ids=\"$id\"]") as? HTMLElement)?.let { it.focus(); glow(it) }
        }
        return true
    }

    /** A character typed: on a part someone else holds, focused, its border glows; false anywhere else. */
    fun typed(): Boolean {
        val at = document.activeElement as? HTMLElement ?: return false
        if (at.parentElement !== root || !at.hasAttribute("data-holder")) return false
        glow(at)
        return true
    }

    /** The tab gives its part back. */
    fun leave() {
        val id = part ?: return
        if (leaving) return
        leaving = true
        steps += Step("write", ::text)
        steps += Step("release", { Json.Object("id" to id) })
        run()
    }

    private fun tell() {
        if (steps.none { it.type == "write" }) steps += Step("write", ::text)
        run()
    }

    private fun run() {
        if (busy) return
        val step = steps.removeFirstOrNull() ?: return
        val payload = step.payload() ?: return run()
        busy = true
        act(step.type, payload).then<Unit>(
            onFulfilled = { busy = false; step.done(it); run() },
            onRejected = { busy = false; step.failed(it); run() })
    }

    /** What the part says now and where its holder is in it; null when the room was told already. */
    private fun text(): Json.Object? {
        val area = area ?: return null
        val id = part ?: return null
        val text = begun?.let { area.value.take(it).trimEnd() } ?: area.value
        val caret = (area.selectionStart ?: text.length).coerceAtMost(text.length)
        val now = "$caret\n$text"
        if (now == said) return null
        said = now
        return Json.Object("id" to id, "text" to text, "caret" to caret)
    }

    /** Whether the line just broken leaves a blank one behind a paragraph: the caret stands where another would begin. */
    private fun begins(area: HTMLTextAreaElement): Boolean {
        val before = area.value.take(area.selectionStart ?: return false)
        return before.endsWith("\n") && cut(before + "x").let { it.size > 1 && it.last() == "x" }
    }

    private fun begin(area: HTMLTextAreaElement) {
        val id = part ?: return
        begun = area.selectionStart ?: return
        steps += Step("write", ::text)
        steps += Step("take", { Json.Object("after" to id) }, failed = { begun = null; tell() })   // not born: the text goes on where it is
        run()
    }

    /** Whether the caret stands on the textarea's first ([up]) or last line, wrapped lines counted. */
    private fun onEdge(area: HTMLTextAreaElement, up: Boolean): Boolean =
        top(area, area.selectionStart ?: 0) == top(area, if (up) 0 else area.value.length)

    /** How far down the textarea [index] is laid out: measured on a hidden copy, the text after it included (a word wraps whole). */
    private fun top(area: HTMLTextAreaElement, index: Int): Int {
        val style = window.getComputedStyle(area)
        val copy = document.createElement("div") as HTMLElement
        for (property in LAYOUT) copy.style.setProperty(property, style.getPropertyValue(property))
        copy.style.cssText += "position: absolute; visibility: hidden; left: -9999px; top: 0; border: 0; height: auto;" +
            "box-sizing: border-box; width: ${area.clientWidth}px"
        val mark = document.createElement("span") as HTMLElement
        mark.textContent = "\u2060"   // zero width, and no line break on either side
        copy.appendChild(document.createTextNode(area.value.take(index)))
        copy.appendChild(mark)
        copy.appendChild(document.createTextNode(area.value.drop(index)))
        document.body!!.appendChild(copy)
        return mark.offsetTop.also { copy.remove() }
    }

    private fun held(target: Any?): HTMLTextAreaElement? = (target as? HTMLTextAreaElement)?.takeIf { it === area }

    /** A key that did nothing on [part]: `data-kroom-refused` a moment (text.css makes its border glow), again at each key. */
    private fun glow(part: HTMLElement) {
        part.removeAttribute(REFUSED)
        part.offsetWidth   // a layout in between: the animation starts over
        part.setAttribute(REFUSED, "")
        window.clearTimeout(part.asDynamic().kroomGlow as? Int ?: 0)
        part.asDynamic().kroomGlow = window.setTimeout({ part.removeAttribute(REFUSED) }, 250)
    }

    private fun arm() {
        timer?.let { window.clearTimeout(it) }
        timer = if (idle > 0 && part != null) window.setTimeout({ leave() }, idle) else null
    }

    private fun forget() {
        timer?.let { window.clearTimeout(it) }
        timer = null; area = null; part = null; said = null; begun = null; leaving = false
    }

    private companion object {
        const val PART = "[data-kroom-part], [data-kroom-ids]"
        const val REFUSED = "data-kroom-refused"
        /** What lays a textarea's text out, copied to measure it. */
        val LAYOUT = listOf("font-family", "font-size", "font-weight", "font-style", "font-variant", "letter-spacing", "word-spacing",
            "line-height", "text-transform", "text-indent", "tab-size", "white-space", "overflow-wrap", "word-break",
            "padding-top", "padding-right", "padding-bottom", "padding-left")
    }
}

/**
 * A text's region: [template] merged under [root], the part this tab holds typed in through a [Writer], the region's
 * triggers posting in the writer's turn. The page memory is a [TextPage] unless the page has one of its own. With
 * kroom-webapp-assets' keys.js (`window.kroomKeys`), the arrows and the page keys move the writer, the focus in [root],
 * and a character typed on a part someone else holds makes it glow.
 */
fun <S : TextState> Room<S>.write(template: String, root: Element, idle: Int = 5000): Writer {
    if (page == null) page = TextPage(this, js("api").tabId() as String?)   // api.js owns the tab id
    val writer = Writer(root, { type, payload -> act(type, payload) }, idle, state.cut)
    attach(template, root) { type, payload -> writer.post(type, payload) }
    react { _, _ -> writer.merged() }
    val keys = window.asDynamic().kroomKeys
    if (keys != null) {
        for ((key, up, byPage) in MOVES) keys.on(OWNER, key, root, { _: dynamic -> writer.move(up, byPage) })
        keys.on(OWNER, "Char", root, { _: dynamic -> writer.typed() })
    }
    return writer
}

/** Who registers the editor's keys. */
internal const val OWNER = "kroom-markdown-editor"

private val MOVES = listOf(Triple("ArrowUp", true, false), Triple("ArrowDown", false, false), Triple("PageUp", true, true), Triple("PageDown", false, true))
