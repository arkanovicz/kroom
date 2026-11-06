package com.republicate.kroom.client

import com.republicate.kroom.event.ChatEvents
import com.republicate.kroom.event.ChatState
import kotlinx.browser.document
import kotlinx.browser.window
import org.apache.velocity.engine.VelocityEngine
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins what the tab posts as its presence: on change only, nothing as a spectator, again after a snapshot when not ONLINE, nothing once going away. */
class PresenceTest {

    private fun posted() = (js("window.posted") as Array<String>).toList()
    private fun hidden(on: Boolean) { js("window.hiddenNow = on"); document.dispatchEvent(Event("visibilitychange")) }
    private fun after(ms: Int): Promise<Unit> = Promise { resolve, _ -> window.setTimeout({ resolve(Unit) }, ms) }

    @Test fun theTabReportsItsPresenceOnChange(): Promise<Unit> {
        js("""
            window.posted = []; window.hiddenNow = false;
            Object.defineProperty(document, 'hidden', { get: function() { return window.hiddenNow; }, configurable: true });
            window.api = { base: '/', tabId: function() { return null; },
                           act: function(room, type, payload) { window.posted.push(type + ':' + payload.status); return Promise.resolve({}); } };
        """)
        val room = Room("chat", ChatState(), ChatEvents(), VelocityEngine(null))
        room.frame("state", """{"history":[]}""")
        val stop = room.report(idleAfter = 40)
        hidden(true)
        assertEquals(emptyList(), posted())   // a spectator: AWAY noted, nothing posted
        room.frame("state", """{"history":[],"me":{"id":"a","name":"Alice"}}""")
        assertEquals(listOf("status:AWAY"), posted())   // the snapshot made a member: what the room does not know, posted
        hidden(false)
        assertEquals(listOf("status:AWAY", "status:ONLINE"), posted())
        return after(80).then {
            assertEquals("status:IDLE", posted().last())
            document.dispatchEvent(Event("mousemove"))
            document.dispatchEvent(Event("mousemove"))
            assertEquals(listOf("status:AWAY", "status:ONLINE", "status:IDLE", "status:ONLINE"), posted())   // once: on change only
            room.frame("state", """{"history":[],"me":{"id":"a","name":"Alice"}}""")
            assertEquals(4, posted().size)   // a reconnect while ONLINE: the room starts a joiner ONLINE, nothing to say
            hidden(true)
            room.frame("state", """{"history":[],"me":{"id":"a","name":"Alice"}}""")
            assertEquals(listOf("status:AWAY", "status:AWAY"), posted().takeLast(2))   // a reconnect while AWAY: said again
            window.dispatchEvent(Event("pagehide"))
            hidden(false)
            assertEquals(6, posted().size)   // going away (a reload: pagehide, then the visibility change): nothing to report
            window.dispatchEvent(Event("pageshow"))
            assertEquals("status:ONLINE", posted().last())   // back from the cache: what changed meanwhile, said
            stop()
            hidden(true)
            assertEquals(7, posted().size)
        }
    }
}
