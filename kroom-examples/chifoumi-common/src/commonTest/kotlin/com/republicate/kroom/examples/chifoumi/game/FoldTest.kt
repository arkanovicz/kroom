package com.republicate.kroom.examples.chifoumi.game

import com.republicate.kroom.event.Store
import com.republicate.kson.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** The recording a room made (LobbyTest, bob's tab): `frames.jsonl`, as the platform reads it. */
expect fun golden(): String

/** The browser's fold is the server's: the same class, on every platform, reaches the snapshot the room would serve. */
class FoldTest {
    @Test fun `the fold of the recorded frames is the room's snapshot`() {
        val store = Store(LobbyState(), LobbyLogic.registry, "b")
        var checked = false
        golden().lines().filter { it.isNotBlank() }.map { Json.parse(it) as Json.Object }.forEach { line ->
            val event = line.getString("event")!!
            // as text: kson's equals tells a parsed 1 (a Long) from a counted 1 (an Int)
            if (event == "final") { assertEquals(line.getObject("data").toString(), store.json.toString()); checked = true }
            else store.fold(event, line.getObject("data")!!)
        }
        assertEquals(true, checked)
        assertEquals(listOf("b"), store.users.map { it.id })   // alice left
        assertEquals(emptyList(), store.pending)
        assertEquals(1, store.state.history.size)
        assertEquals(emptyMap(), store.state.matches)
        assertEquals(1, store.state.stats!!.played)
    }
}
