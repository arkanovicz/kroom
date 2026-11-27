@file:OptIn(ExperimentalJsExport::class)
package com.republicate.kroom.markdown.editor

import com.republicate.kroom.client.Room
import com.republicate.kroom.client.connect
import com.republicate.kroom.event.TextEvents
import com.republicate.kroom.event.TextState
import kotlinx.browser.document
import kotlinx.browser.window
import org.apache.velocity.engine.Config
import org.apache.velocity.engine.VelocityEngine
import org.apache.velocity.engine.resource.StockResourceLoader
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.asList
import velocity.templates.kroom.VelocityTemplates

/**
 * A markdown text several people write, in [frame] (an element of class `kroom-editor`): the region
 * `kroom/text.html` merged under its `.kroom-text`, the part this tab holds typed in through a [Writer]. Around it,
 * what a text does not know of: kroom-webapp-assets' markdown toolbar (`window.kroomMarkdown`, markdown.js, loaded
 * first) put at the head of its `.kroom-editor-bar` and acting on the part held, its shortcuts (through keys.js), and `data-writing`
 * on the frame while there is one; with [preview], the URL answering the text as html, its `.kroom-editor-preview`
 * pane, opened by its `.kroom-editor-preview-toggle`. Each piece is optional but the `.kroom-text`.
 */
fun <S : TextState> Room<S>.edit(frame: Element, preview: String? = null, idle: Int = 5000): Writer {
    val root = frame.querySelector(".kroom-text") ?: error("no .kroom-text in the editor's frame")
    val writer = write("kroom/text.html", root, idle)
    val held = { root.querySelector("textarea[data-kroom-part]") }
    val markdown = window.asDynamic().kroomMarkdown
    val bar = frame.querySelector(".kroom-editor-bar")
    if (bar != null && markdown != null) {
        bar.insertBefore(markdown.toolbar(held).unsafeCast<Node>(), bar.firstChild)
        markdown.keys(OWNER, root, held)
    }
    val pane = preview?.let { Preview(frame, it) }
    react { name, _ ->
        // the toolbar has something to act on while this tab holds a part
        if (held() != null) frame.setAttribute("data-writing", "") else frame.removeAttribute("data-writing")
        if (name in TEXT) pane?.follow()
    }
    return writer
}

/**
 * The editor for a page with no Kotlin of its own (kroom-webapp-assets' `editor.js`): `kroomEditor.open(frame)`, the
 * room named by the frame's `data-room`, the preview's URL by its `data-preview` (both may be given instead). At
 * load, every `.kroom-editor[data-room]` of the document is opened. A page with a Kotlin/JS bundle of its own links
 * this module and calls [edit]: two bundles are two runtimes.
 */
@JsExport
object kroomEditor {
    fun open(frame: Element, room: String? = null, preview: String? = null, idle: Int = 5000) {
        val name = room ?: frame.getAttribute("data-room") ?: error("no room named for the editor")
        Room(name, TextState(), TextEvents(), engine).apply { edit(frame, preview ?: frame.getAttribute("data-preview"), idle) }.connect()
    }
}

/** The bundle's entry: the global, and the frames already in the page. Not run where this module is linked. */
fun main() {
    window.asDynamic().kroomEditor = kroomEditor
    val start = { document.querySelectorAll(".kroom-editor[data-room]").asList().forEach { kroomEditor.open(it as Element) } }
    if (document.readyState.toString() == "loading") document.addEventListener("DOMContentLoaded", { start() }) else start()
}

/** The frames after which the text may say something else. */
private val TEXT = setOf("state", "take", "write", "release")

/** The preview's pane and its button: the text as html, asked when the pane opens and, while it is open, a moment after the text moved. */
private class Preview(private val frame: Element, private val url: String) {
    private val pane = frame.querySelector(".kroom-editor-preview") as? HTMLElement
    private val toggle = frame.querySelector(".kroom-editor-preview-toggle") as? HTMLElement
    private var open = false
    private var timer: Int? = null

    init {
        toggle?.addEventListener("mousedown", { it.preventDefault() })   // the textarea keeps the focus: looking is not leaving
        toggle?.addEventListener("click", {
            open = !open
            pane?.hidden = !open
            if (open) frame.setAttribute("data-previewing", "") else frame.removeAttribute("data-previewing")
            toggle.setAttribute("aria-pressed", open.toString())
            if (open) refresh()
        })
    }

    fun follow() {
        if (!open) return
        timer?.let { window.clearTimeout(it) }
        timer = window.setTimeout({ refresh() }, 400)
    }

    private fun refresh() {
        window.asDynamic().fetch(url).then { r: dynamic -> r.text() }.then { html: dynamic -> pane?.innerHTML = html as String }
    }
}

/** The region is this module's, compiled: an engine without an interpreter, so no parser ships. */
private val engine = VelocityEngine(null, Config(informalNavigation = true)).apply { addResourceLoader("stock", StockResourceLoader(VelocityTemplates::resolve)) }
