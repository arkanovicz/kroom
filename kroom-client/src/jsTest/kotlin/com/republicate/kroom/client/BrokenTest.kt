package com.republicate.kroom.client

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins what a broken region looks like: seen where the page says nothing, the page's own rule where it says something. */
class BrokenTest {

    private fun root() = (document.createElement("div") as HTMLElement).also { document.body!!.appendChild(it) }
    private fun outline(root: HTMLElement) = window.getComputedStyle(root).getPropertyValue("outline-style")
    private fun mark(root: HTMLElement) = window.getComputedStyle(root, "::before").getPropertyValue("content")

    @Test fun aBrokenRegionIsSeenUnlessThePageSaysHow() {
        showBroken(); showBroken()
        val root = root()
        assertEquals("none", outline(root))
        assertEquals("none", mark(root))

        root.setAttribute(Room.BROKEN, "")
        assertEquals("dashed", outline(root))
        assertEquals("\"⚠\"", mark(root))

        // the page's rule, however light and wherever it stands, wins over the default
        val page = document.createElement("style").also { it.textContent = "[${Room.BROKEN}] { outline-style: dotted; }"; document.head!!.appendChild(it) }
        assertEquals("dotted", outline(root))

        root.removeAttribute(Room.BROKEN)
        assertEquals("none", outline(root))
        page.remove(); root.remove()
    }
}
