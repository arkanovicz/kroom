package com.republicate.kroom.client

import com.republicate.kroom.event.Part
import com.republicate.kroom.event.TextState
import com.republicate.kson.Json
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
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
 */
class Writer(private val root: Element, private val act: (String, Json.Object) -> Promise<dynamic>, private val idle: Int = 5000) {

    private class Step(val type: String, val payload: () -> Json.Object?, val done: (dynamic) -> Unit = {}, val failed: (dynamic) -> Unit = {})

    private val steps = ArrayDeque<Step>()
    private var busy = false

    private var area: HTMLTextAreaElement? = null   // the textarea of the part held
    private var part: String? = null                // its id
    private var said: String? = null                // what the room was last told of it
    private var begun: Int? = null                  // a paragraph was begun: where, in the textarea, what goes on in it starts
    private var leaving = false                     // its release is asked
    private var timer: Int? = null

    init {
        root.addEventListener("input", { e ->
            held(e.target)?.let { area ->
                arm()
                if (leaving || begun != null) return@let
                if (e.asDynamic().inputType == "insertLineBreak" && begins(area)) begin(area) else tell()
            }
        })
        for (type in listOf("keyup", "mouseup")) root.addEventListener(type, { e -> held(e.target)?.let { arm(); if (!leaving && begun == null) tell() } })
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
        now.setSelectionRange(now.value.length, now.value.length)
        arm()
        tell()
    }

    /** An intent of this text's, in its turn: after what this tab already said, the text as typed first. */
    fun post(type: String, payload: Json.Object): Promise<dynamic> = Promise { resolve, reject ->
        steps += Step("write", ::text)
        steps += Step(type, { payload }, resolve, { reject(it.unsafeCast<Throwable>()) })
        run()
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
        return before.endsWith("\n") && Part.cut(before + "x").let { it.size > 1 && it.last() == "x" }
    }

    private fun begin(area: HTMLTextAreaElement) {
        val id = part ?: return
        begun = area.selectionStart ?: return
        steps += Step("write", ::text)
        steps += Step("take", { Json.Object("after" to id) }, failed = { begun = null; tell() })   // not born: the text goes on where it is
        run()
    }

    private fun held(target: Any?): HTMLTextAreaElement? = (target as? HTMLTextAreaElement)?.takeIf { it === area }

    private fun arm() {
        timer?.let { window.clearTimeout(it) }
        timer = if (idle > 0 && part != null) window.setTimeout({ leave() }, idle) else null
    }

    private fun forget() {
        timer?.let { window.clearTimeout(it) }
        timer = null; area = null; part = null; said = null; begun = null; leaving = false
    }
}

/**
 * A text's region: [template] merged under [root], the part this tab holds typed in through a [Writer], the region's
 * triggers posting in the writer's turn. The page memory is a [TextPage] unless the page has one of its own.
 */
fun <S : TextState> Room<S>.write(template: String, root: Element, idle: Int = 5000): Writer {
    if (page == null) page = TextPage(this, api.tabId() as String?)
    val writer = Writer(root, { type, payload -> act(type, payload) }, idle)
    attach(template, root) { type, payload -> writer.post(type, payload) }
    react { _, _ -> writer.merged() }
    return writer
}
