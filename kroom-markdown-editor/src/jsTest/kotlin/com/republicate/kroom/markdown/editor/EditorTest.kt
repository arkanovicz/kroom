package com.republicate.kroom.markdown.editor

import com.republicate.kroom.client.Room
import com.republicate.kroom.event.TextEvents
import com.republicate.kroom.event.TextState
import kotlinx.browser.document
import kotlinx.browser.window
import org.apache.velocity.engine.Config
import org.apache.velocity.engine.VelocityEngine
import org.apache.velocity.engine.resource.StockResourceLoader
import org.w3c.dom.HTMLElement
import velocity.templates.kroom.VelocityTemplates
import kotlin.js.Promise
import kotlin.test.*

/** Pins the editor around the writer: the compiled region merged in the frame, the toolbar, `data-writing`, the preview asked where the page says. */
class EditorTest {

    private fun tick(ms: Int = 0): Promise<Unit> = Promise { resolve, _ -> window.setTimeout({ resolve(Unit) }, ms) }

    @Test fun theFrameGetsTheRegionTheToolbarWhileAPartIsHeldAndThePreviewFromItsUrl(): Promise<Unit> {
        js("""
            window.posted = []; window.fetched = [];
            window.api = { base: '/api/', tabId: function() { return 't1'; },
                           act: function(room, type, payload) { window.posted.push(type); return Promise.resolve({}); } };
            window.kroomMarkdown = { toolbar: function(target) { var d = document.createElement('div'); d.className = 'kroom-format'; window.target = target; return d; },
                                     shortcut: function() {} };
            window.fetch = function(url) { window.fetched.push(url); return Promise.resolve({ text: function() { return Promise.resolve('<p>One.</p>'); } }); };
        """)
        val frame = (document.createElement("section") as HTMLElement).also { document.body!!.appendChild(it) }
        frame.className = "kroom-editor"
        frame.innerHTML = """<nav class="kroom-editor-bar"><button class="kroom-editor-preview-toggle">Preview</button></nav>""" +
            """<div class="kroom-text"></div><article class="kroom-editor-preview" hidden></article>"""
        val engine = VelocityEngine(null, Config(informalNavigation = true)).apply { addResourceLoader("stock", StockResourceLoader(VelocityTemplates::resolve)) }
        val room = Room("draft", TextState(), TextEvents(), engine)
        room.edit(frame, "/api/draft/html")

        val bar = frame.querySelector(".kroom-editor-bar")!!
        assertEquals("kroom-format", bar.firstElementChild?.className)   // the toolbar heads the bar
        room.frame("state", """{"parts":[{"id":"1","text":"One."}],"last":1,"me":{"id":"a","name":"Alice"}}""")
        assertEquals("One.", frame.querySelector(".kroom-text p")?.textContent)
        assertFalse(frame.hasAttribute("data-writing"))

        room.frame("take", """{"ids":["1"],"user":"a","client":"t1"}""")
        val area = frame.querySelector(".kroom-text textarea[data-kroom-part='1']")
        assertNotNull(area)
        assertTrue(frame.hasAttribute("data-writing"))
        assertSame(area, js("window.target()"))                         // the toolbar acts on the part held

        (frame.querySelector(".kroom-editor-preview-toggle") as HTMLElement).click()
        assertEquals(listOf("/api/draft/html"), (js("window.fetched") as Array<String>).toList())
        return tick().then {
            assertEquals("<p>One.</p>", frame.querySelector(".kroom-editor-preview")?.innerHTML)
            room.frame("release", """{"id":"1"}""")
            assertFalse(frame.hasAttribute("data-writing"))
            frame.remove()
        }
    }

    @Test fun aLinkedModuleRunsNoEntry() {
        assertEquals(undefined, window.asDynamic().kroomEditor)        // the bundle's main is the bundle's
    }
}
