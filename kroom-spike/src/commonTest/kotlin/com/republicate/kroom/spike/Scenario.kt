package com.republicate.kroom.spike

import com.republicate.kroom.client.Room
import com.republicate.kroom.event.Chat
import com.republicate.kroom.event.ChatState
import com.republicate.kroom.event.Registry
import org.apache.velocity.engine.VelocityContext
import org.apache.velocity.engine.VelocityEngine
import org.apache.velocity.engine.dom.Tree
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The §6.2 page on a [Room]: every frame folded and merged into the tree, which must then read as the text render
 * of the same State (escaped by the engine, the sink raw). An item's node survives its own change and its
 * neighbours' arrivals and departures.
 */
class Scenario<N : Any>(
    private val engine: VelocityEngine,
    tree: Tree<N>,
    root: N,
    /** The root's children serialized as HTML. */
    private val html: () -> String,
    /** The root's `<ul id="users">` items. */
    private val items: () -> List<N>,
    private val attribute: (N, String) -> String?,
    /** After each frame: the tree's writes since the frame, for the tree that logs them. */
    private val afterFrame: (name: String) -> Unit = {},
) {
    private val room = Room("chat", ChatState(), Registry(::Chat), engine).attach("page.vtl", tree, root)

    private fun frame(name: String, json: String) {
        room.frame(name, json)
        assertEquals(engine.getTemplate("page.vtl").merge(VelocityContext(mutableMapOf("state" to room.state))), html(), "the tree is the text render")
        afterFrame(name)
    }

    fun run() {
        frame("state", """{"users":[{"id":"a","name":"alice","status":"ONLINE"}],"history":[]}""")
        val alice = items().single()
        assertEquals("users.a", attribute(alice, "id"))

        frame("status", """{"user":"a","status":"AWAY"}""")
        assertSame(alice, items().single())
        assertEquals("user AWAY", attribute(alice, "class"))

        frame("join", """{"user":{"id":"b","name":"bob","status":"ONLINE"},"first":true,"count":2}""")
        assertSame(alice, items()[0])
        assertEquals("users.b", attribute(items()[1], "id"))

        frame("chat", """{"text":"hi <all> & co","seq":1,"from":"b","author":{"id":"b","name":"bob"},"timestamp":"t"}""")
        check("<li>bob: hi &lt;all&gt; &amp; co</li>" in html())

        val bob = items()[1]
        frame("leave", """{"user":{"id":"a","name":"alice","status":"AWAY"},"last":true,"count":1}""")
        assertSame(bob, items().single())
    }
}
