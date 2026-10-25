package com.republicate.kroom.spike

import com.republicate.kroom.event.Chat
import com.republicate.kroom.event.ChatState
import com.republicate.kroom.event.Registry
import com.republicate.kroom.event.Store
import org.apache.velocity.engine.Context
import org.apache.velocity.engine.VelocityContext
import org.apache.velocity.engine.dom.HtmlDomSink
import org.apache.velocity.engine.dom.Tree
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The §6.2 page over the client's fold, merged once into a tree through the engine's DOM sink, then merged
 * again after each frame. The same story on every tree and pipeline: the tree always serializes to the text
 * render of the same state (escaped by the engine, the sink raw), an item's node survives its own change and
 * its neighbours' arrivals and departures.
 */
class Scenario<N : Any>(
    tree: Tree<N>,
    root: N,
    /** The root's children serialized as HTML. */
    private val html: () -> String,
    /** The root's `<ul id="users">` items. */
    private val items: () -> List<N>,
    private val attribute: (N, String) -> String?,
    /** The page as text, no sink; the oracle. */
    private val text: (Context) -> String,
    private val render: (Context, Appendable) -> Unit,
    /** After each frame: the tree's writes since the frame, for the tree that logs them. */
    private val afterFrame: (name: String) -> Unit = {},
) {
    private val store = Store(ChatState(), Registry(::Chat))
    private val sink = HtmlDomSink(tree, root, ::keyOf)

    private fun context() = VelocityContext(mutableMapOf("state" to ChatView(store)))

    private fun pass() {
        render(context(), sink)
        assertEquals(text(context()), html())
    }

    private fun frame(name: String, json: String) { store.fold(name, json); pass(); afterFrame(name) }

    fun run() {
        store.fold("state", """{"users":[{"id":"a","name":"alice","status":"ONLINE"}],"history":[]}""")
        pass(); afterFrame("state")
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
