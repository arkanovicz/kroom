package com.republicate.kroom.examples.chifoumi.game

import com.republicate.kroom.event.Store
import com.republicate.kson.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The recording a room made (LobbyTest, bob's tab): `frames.jsonl`, as the platform reads it. */
expect fun golden(): String

/** The browser's fold is the server's: the same class, on every platform, reaches the snapshot the room would serve. */
class FoldTest {
    @Test fun `the fold of the recorded frames is the room's snapshot`() {
        val store = Store(LobbyState(), LobbyEvents)
        var checked = false
        golden().lines().filter { it.isNotBlank() }.map { Json.parse(it) as Json.Object }.forEach { line ->
            val event = line.getString("event")!!
            if (event == "final") { assertEquals(line.getObject("data"), store.json); checked = true }
            else store.fold(event, line.getObject("data")!!)
        }
        assertEquals(true, checked)
        assertEquals(listOf("b"), store.state.users.map { it.id })   // alice left
        assertEquals(1, store.state.history.size)
        assertTrue(store.state.matches.isEmpty())
        assertEquals(1, store.state.stats!!.played)
    }
}
