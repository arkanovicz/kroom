package com.republicate.kroom.client

import com.republicate.kroom.event.ChatEvents
import com.republicate.kroom.event.ChatState
import com.republicate.kroom.event.TextEvents
import com.republicate.kroom.event.TextState
import kotlinx.browser.document
import kotlinx.browser.window
import org.apache.velocity.engine.VelocityEngine
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins two rooms on one page (a text over a chat): a stream each, one tab id, each root's triggers and each room's presence its own. */
class TwoRoomsTest {

    private fun opened() = (js("window.opened") as Array<String>).toList()
    private fun posted() = (js("window.posted") as Array<String>).toList()
    private fun hidden(on: Boolean) { js("window.hiddenNow = on"); document.dispatchEvent(Event("visibilitychange")) }
    private fun tick(): Promise<Unit> = Promise { resolve, _ -> window.setTimeout({ resolve(Unit) }, 0) }

    /** A root as a region's merge leaves it, its triggers bound as `attach` binds them. */
    private fun root(room: Room<*>, html: String): HTMLElement {
        val root = document.createElement("div") as HTMLElement
        root.innerHTML = html
        document.body!!.appendChild(root)
        Intents(root, room.store.events) { type, payload -> room.act(type, payload) }
        return root
    }

    @Test fun eachRoomHasItsStreamItsTriggersAndItsPresence(): Promise<Unit> {
        js("""
            window.opened = []; window.posted = []; window.streams = {}; window.hiddenNow = false;
            Object.defineProperty(document, 'hidden', { get: function() { return window.hiddenNow; }, configurable: true });
            window.api = { base: '/api/', tabId: function() { return 't1'; },
                           act: function(room, type, payload) { window.posted.push(room + ' ' + type + ' ' + JSON.stringify(payload)); return Promise.resolve({}); } };
            window.EventSource = function(url) { window.opened.push(url); var on = window.streams[url] = {};
                this.addEventListener = function(name, fn) { on[name] = fn; }; this.close = function() {}; };
        """)
        fun frame(url: String, name: String, data: String) { js("window.streams[url][name]({data: data})") }

        val text = Room("draft", TextState(), TextEvents(), VelocityEngine(null))
        val chat = Room("talk", ChatState(), ChatEvents(), VelocityEngine(null))
        val parts = root(text, """<p data-kroom-intent="take" data-kroom-ids="1">One.</p>""")
        val lines = root(chat, """<form><input name="text" value="hi"><button data-kroom-intent="chat">Say</button></form>""")
        text.connect(); chat.connect()
        val (draft, talk) = opened()
        assertEquals(listOf("/api/rooms/draft/stream?tabId=t1", "/api/rooms/talk/stream?tabId=t1"), listOf(draft, talk))   // one tab, a connection in each room

        // each stream folds into its own room: a name the other does not speak is not even listened to
        frame(draft, "state", """{"parts":[{"id":"1","text":"One."}],"last":1,"me":{"id":"a","name":"Alice"}}""")
        frame(talk, "state", """{"history":[],"me":{"id":"a","name":"Alice"}}""")
        frame(draft, "take", """{"ids":["1"],"user":"b","client":"t9"}""")
        frame(talk, "chat", """{"text":"hello","seq":1}""")
        assertEquals("b", text.state.parts["1"]?.holder)
        assertEquals(1, chat.state.history.size)
        assertEquals(false, js("'chat' in window.streams[draft]") as Boolean)
        assertEquals(false, js("'take' in window.streams[talk]") as Boolean)
        frame(draft, "release", """{"id":"1"}""")

        // a click goes to the room of the root it is under, checked on that room's view
        (parts.querySelector("p") as HTMLElement).click()
        (lines.querySelector("button") as HTMLElement).click()
        assertEquals(listOf("""draft take {"ids":["1"]}""", """talk chat {"text":"hi"}"""), posted())

        return tick().then {
            // presence is a room's: the tab says it to each
            val stop = listOf(text.report(), chat.report())
            hidden(true)
            assertEquals(listOf("""draft status {"status":"AWAY"}""", """talk status {"status":"AWAY"}"""), posted().drop(2))
            stop.forEach { it() }
            hidden(false)
            assertEquals(4, posted().size)
            parts.remove(); lines.remove()
        }
    }
}
