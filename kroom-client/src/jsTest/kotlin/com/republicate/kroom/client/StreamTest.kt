package com.republicate.kroom.client

import com.republicate.kroom.event.ChatEvents
import com.republicate.kroom.event.ChatState
import kotlinx.browser.window
import org.apache.velocity.engine.VelocityEngine
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins the stream's life with the page's: closed on `pagehide`, opened again on `pageshow` (the same URL, the same tab), never once closed. */
class StreamTest {

    private fun opened() = (js("window.opened") as Array<String>).toList()
    private fun closed() = (js("window.shut") as Array<String>).toList()
    private fun pageshow(persisted: Boolean) = window.dispatchEvent(js("new PageTransitionEvent('pageshow', {persisted: persisted})") as Event)

    @Test fun theStreamFollowsThePage() {
        js("""
            window.opened = []; window.shut = [];
            window.api = { base: '/api/', tabId: function() { return 't1'; }, act: function() { return Promise.resolve({}); } };
            window.EventSource = function(url) { window.opened.push(url); this.url = url;
                this.addEventListener = function() {}; this.close = function() { window.shut.push(url); }; };
        """)
        val room = Room("chat", ChatState(), ChatEvents(), VelocityEngine(null))
        val stream = room.connect()
        assertEquals(listOf("/api/rooms/chat/stream?tabId=t1"), opened())
        pageshow(persisted = false)                              // a load's pageshow: the stream is already open
        assertEquals(1, opened().size)
        window.dispatchEvent(Event("pagehide"))                  // into the cache (or unloading): closed, the server hears the drop
        assertEquals(listOf("/api/rooms/chat/stream?tabId=t1"), closed())
        window.dispatchEvent(Event("pagehide"))                  // once
        assertEquals(1, closed().size)
        pageshow(persisted = true)                               // back from the cache: the same stream again, a reconnect
        assertEquals(2, opened().size)
        assertEquals(opened()[0], opened()[1])
        pageshow(persisted = true)                               // not parked: nothing
        assertEquals(2, opened().size)
        stream.close()
        assertEquals(2, closed().size)
        window.dispatchEvent(Event("pagehide")); pageshow(persisted = true)   // closed for good: nothing follows
        assertEquals(2, opened().size); assertEquals(2, closed().size)
    }

    private fun tick(ms: Int): Promise<Unit> = Promise { resolve, _ -> window.setTimeout({ resolve(Unit) }, ms) }

    /** A stream an HTTP error closed: the room offline, intents refused here, the stream opened again a moment later, back online at its snapshot. */
    @Test fun aStreamClosedByAnErrorIsOpenedAgainAndTheRoomIsOfflineMeanwhile(): Promise<Unit> {
        js("""
            window.opened = []; window.shut = []; window.sources = []; window.posted = [];
            window.api = { base: '/api/', tabId: function() { return 't1'; }, act: function(r, type) { window.posted.push(type); return Promise.resolve({}); } };
            window.EventSource = function(url) { window.opened.push(url); window.sources.push(this); var on = this.on = {}; this.readyState = 1;
                this.addEventListener = function(name, fn) { (on[name] = on[name] || []).push(fn); };
                this.fire = function(name, data) { (on[name] || []).forEach(function(fn) { fn({ data: data }); }); };
                this.close = function() { this.readyState = 2; window.shut.push(url); }; };
            window.EventSource.CLOSED = 2;
        """)
        val room = Room("chat", ChatState(), ChatEvents(), VelocityEngine(null))
        val stream = room.connect()
        fun source(i: Int): dynamic = js("window.sources")[i]
        source(0).fire("state", """{"history":[],"me":{"id":"a","name":"Alice"}}""")
        assertFalse(room.offline)
        source(0).readyState = 0; source(0).fire("error")              // dropped: the browser retries by itself
        assertTrue(room.offline)
        assertEquals(1, opened().size)
        source(0).readyState = 1; source(0).fire("state", """{"history":[],"me":{"id":"a","name":"Alice"}}""")
        assertFalse(room.offline)                                       // its snapshot: back
        source(0).readyState = 2; source(0).fire("error")              // a 503: closed for good, by the browser
        assertTrue(room.offline)
        var refused: dynamic = null
        room.act("chat", com.republicate.kson.Json.Object("text" to "hi")).catch { refused = it.asDynamic().data.reason }
        return tick(900).then {
            assertEquals("offline", refused)
            assertEquals(0, (js("window.posted") as Array<String>).size)  // refused here, nothing posted
            assertEquals(1, opened().size)                                // not yet
        }.then { tick(300) }.then<Unit> {
            assertEquals(2, opened().size)                                // a second later
            source(1).readyState = 2; source(1).fire("error")             // still down: it waits again
            window.dispatchEvent(Event("pagehide"))                       // away meanwhile: no reopening
            assertEquals(2, opened().size)
            pageshow(persisted = true)                                    // back: at once
            assertEquals(3, opened().size)
            source(2).fire("state", """{"history":[],"me":{"id":"a","name":"Alice"}}""")
            assertFalse(room.offline)
            stream.close()
        }
    }
}
