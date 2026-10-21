package com.republicate.kroom.spike

import com.republicate.kroom.event.Join
import com.republicate.kroom.event.Leave
import com.republicate.kroom.event.Status
import com.republicate.kson.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatStateTest {
    private fun obj(vararg pairs: Pair<String, Any?>) = Json.MutableObject(mapOf(*pairs))
    private fun user(id: String, name: String) = obj("user" to obj("id" to id, "name" to name, "status" to "ONLINE"))

    @Test
    fun `the fold over JSON is the snapshot`() {
        val storage = JsonStorage()
        val state = ChatState(storage)
        state.reduce(Join(user("5", "Alice")))
        state.reduce(Join(user("7", "Bob")))
        state.reduce(Status(obj("user" to "5", "status" to "AWAY")))
        assertEquals("AWAY", state.users["5"]?.status)
        assertEquals(listOf("Alice", "Bob"), state.users.map { it.name })
        assertEquals(2, state.users.size)
        assertEquals(
            Json.parse("""{"users":{"5":{"name":"Alice","status":"AWAY"},"7":{"name":"Bob","status":"ONLINE"}},"history":{}}"""),
            storage.json
        )
        state.reduce(Leave(user("5", "Alice")))
        assertEquals(listOf("Bob"), state.users.map { it.name })
    }

    @Test
    fun `an absent field takes every write and answers nothing`() {
        val state = ChatState(object : Storage {
            override fun <V : View> keyed(name: String, view: (Values) -> V): Keyed<V> = Absent()
        })
        state.reduce(Join(user("5", "Alice")))
        state.reduce(Status(obj("user" to "5", "status" to "AWAY")))
        assertEquals(0, state.users.size)
        assertEquals(null, state.users["5"])
    }
}
