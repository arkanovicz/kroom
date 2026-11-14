package com.republicate.kroom.plugins.scratchpad

import com.republicate.kroom.client.Room
import com.republicate.kroom.client.connect
import com.republicate.kroom.client.write
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
import velocity.templates.kroom.VelocityTemplates

/**
 * The scratchpad's page: the text's fold is [TextState], the class the server runs; its region is written in through
 * kroom-client's writer. Around it, what a text does not know of: the stock markdown toolbar on the part this tab
 * holds, and a preview, the text as the server renders it, following the frames while it is open.
 */
fun main() {
    val root = document.getElementById("kroom-scratchpad") ?: return
    val room = Room(root.getAttribute("data-room") ?: "scratchpad", TextState(), TextEvents(), engine)
    room.write("kroom/text.html", root)

    val frame = root.closest(".kroom-scratchpad-frame")
    val held = { root.querySelector("textarea[data-kroom-part]") }
    val markdown = window.asDynamic().kroomMarkdown
    val bar = frame?.querySelector(".kroom-scratchpad-bar")
    if (bar != null && markdown != null) {
        bar.insertBefore(markdown.toolbar(held).unsafeCast<Node>(), bar.firstChild)
        root.addEventListener("keydown", { e -> held()?.let { markdown.shortcut(e, it) } })
    }
    val preview = frame?.let { Preview(it) }
    room.react { name, _ ->
        // the toolbar has something to act on while this tab holds a part
        frame?.let { if (held() != null) it.setAttribute("data-writing", "") else it.removeAttribute("data-writing") }
        if (name in TEXT) preview?.follow()
    }
    room.connect()
}

/** The frames after which the text may say something else. */
private val TEXT = setOf("state", "take", "write", "release")

/** The preview's pane and its button: the text as html, asked when the pane opens and, while it is open, a moment after the text moved. */
private class Preview(private val frame: Element) {
    private val pane = frame.querySelector(".kroom-scratchpad-preview") as? HTMLElement
    private val toggle = frame.querySelector(".kroom-scratchpad-preview-toggle") as? HTMLElement
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
        window.asDynamic().fetch("/api/scratchpad/html").then { r: dynamic -> r.text() }.then { html: dynamic -> pane?.innerHTML = html as String }
    }
}

/** The region is kroom-client-text's, compiled: an engine without an interpreter, so no parser ships. */
private val engine = VelocityEngine(null, Config(informalNavigation = true)).apply { addResourceLoader("stock", StockResourceLoader(VelocityTemplates::resolve)) }
