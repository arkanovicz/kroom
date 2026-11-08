package com.republicate.kroom.client

import com.republicate.kroom.event.ChatEvents
import com.republicate.kroom.event.ChatState
import kotlinx.browser.window
import org.apache.velocity.engine.VelocityEngine
import org.w3c.dom.events.Event
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
