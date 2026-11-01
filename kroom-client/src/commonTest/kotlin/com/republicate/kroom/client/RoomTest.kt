package com.republicate.kroom.client

import com.republicate.kroom.event.Chat
import com.republicate.kroom.event.ChatState
import com.republicate.kroom.event.Event
import com.republicate.kroom.event.ChatEvents
import com.republicate.kroom.event.State
import com.republicate.kson.Json
import org.apache.velocity.engine.VelocityEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoomTest {
    /** Page memory: what the room's frames left behind, as a page remembers them. */
    private class Seen(private val room: () -> Room<ChatState>) : State() {
        val lines = ArrayList<String>()
        override fun reduce(event: Event) { if (event is Chat) lines += "${event.text} (${room().state.history.size} in history)" }
    }

    @Test fun `a frame folds into the state then the page memory then reacts`() {
        lateinit var room: Room<ChatState>
        val seen = Seen { room }
        val reacted = ArrayList<String>()
        room = Room("chat", ChatState(), ChatEvents(), VelocityEngine(null)).apply { page = seen }
            .react { name, event -> reacted += "$name:${event?.name}:${seen.lines.size}" }
        room.frame("state", """{"history":[]}""")
        room.frame("chat", """{"text":"hi","seq":1}""")
        room.frame("unknown", "{}")
        assertEquals(listOf("hi (1 in history)"), seen.lines)
        assertEquals(listOf("state:null:0", "chat:chat:1", "unknown:null:1"), reacted)
    }

    @Test fun `a reaction to one name gets its event typed`() {
        val events = ChatEvents()
        val heard = ArrayList<String>()
        val room = Room("chat", ChatState(), events, VelocityEngine(null)).on(events.chat) { chat -> heard += chat.text }
        room.frame("state", """{"history":[]}""")
        room.frame("chat", """{"text":"hi","seq":1}""")
        room.frame("join", """{"user":{"id":"a"}}""")
        assertEquals(listOf("hi"), heard)
    }

    @Test fun `an intent is checked on the viewer's view before its POST`() {
        val room = Room("chat", ChatState(), ChatEvents(), VelocityEngine(null))
        room.frame("state", """{"history":[]}""")
        assertNull(room.check("chat", Json.Object("text" to " ")))   // a spectator: the server's to refuse
        room.frame("state", """{"history":[],"me":{"id":"a","name":"Alice"}}""")
        assertEquals("blank", room.check("chat", Json.Object("text" to " "))?.key)
        assertEquals("missing", room.check("chat", Json.Object())?.key)   // the shape first
        assertNull(room.check("chat", Json.Object("text" to "hi")))
        assertNull(room.check("nope", Json.Object()))
    }
}
