package com.republicate.kroom.spike

import com.republicate.kroom.event.Chat
import com.republicate.kroom.event.ChatState
import com.republicate.kroom.event.Registry
import com.republicate.kroom.event.Store
import com.republicate.kroom.event.UserPresence
import org.apache.velocity.engine.Config
import org.apache.velocity.engine.Context
import org.apache.velocity.engine.RefSite
import org.apache.velocity.engine.ReferenceInsertionEvents
import org.apache.velocity.engine.VelocityContext
import org.apache.velocity.engine.VelocityEngine
import org.apache.velocity.engine.jvm.ScriptingCompiler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The engine instantiates its insertion handler by class name; the sink in force is the one this object holds. */
object Insertions : ReferenceInsertionEvents {
    var sink: DomSink<*>? = null
    override fun referenceInsert(context: Context, reference: String, value: Any?, site: RefSite): Any? {
        sink?.expect(reference)
        return value ?: ""   // a null would take the null-reference path and append nothing: the sink wants one append per value
    }
}

/** What the page reads: presence and the chat's tail, over the client's fold. */
class ChatView(private val store: Store<ChatState>) {
    val users: List<UserPresence> get() = store.users
    val history: List<Line> get() = store.state.history.map(::Line)
}

/** A chat line as the page reads it: `author` is a plain JSON object on the event, typed navigation wants a property. */
class Line(val chat: Chat) {
    val seq get() = chat.seq
    val author get() = chat.author?.getString("name")
    val text get() = chat.text
}

/**
 * The §6.2 page, rendered once into a tree through the sink, then updated by re-rendering after each frame:
 * every pass leaves the tree equal to the text render of the same state, a `status` touches two sites and
 * keeps the item's node, a `join` adds one item, a `leave` removes one.
 */
class SinkTest {
    private val page = """
        <span>${'$'}state.users.size</span> online
        <ul id="users">#@each(${'$'}state.users, ${'$'}user)<li id="users.${'$'}user.id" class="user ${'$'}user.status">${'$'}user.name (${'$'}user.status)</li>#end</ul>
        <ul id="chat">#@each(${'$'}state.history, ${'$'}c)<li>${'$'}c.author: ${'$'}c.text</li>#end</ul>
        #set(${'$'}many = ${'$'}state.users.size > 1)#@when(${'$'}many)<p>company</p>#end
    """.trimIndent()

    private val store = Store(ChatState(), Registry(::Chat))
    private val engine = VelocityEngine(ScriptingCompiler(), Config(informalNavigation = true, referenceInsertionEventClasses = listOf(Insertions::class.java.name))).apply {
        addMacro("each", Each({ Insertions.sink }) { when (it) { is UserPresence -> it.id; is Line -> it.seq.toString(); else -> it.toString() } })
        addMacro("when", When { Insertions.sink })
    }
    private val tree = TreeDouble()
    private val root = tree.element("body")
    private val sink = DomSink(tree, root)

    private fun context() = VelocityContext(mutableMapOf("state" to ChatView(store)))

    /** The page as text, no sink: what the tree must serialize to. */
    private fun text(): String { Insertions.sink = null; return engine.evaluate(page, context(), "page") }

    private fun render() {
        Insertions.sink = sink
        sink.pass { engine.evaluate(page, context(), sink, "page") }
        Insertions.sink = null
    }

    private fun frame(name: String, json: String) { store.fold(name, json); tree.clearLog(); render() }

    private fun users() = (root.children.first { it is DElement && it.tag == "ul" } as DElement).children.filterIsInstance<DElement>()

    @Test fun `the page folds into the tree frame by frame`() {
        store.fold("state", """{"users":[{"id":"a","name":"alice","status":"ONLINE"}],"history":[]}""")
        render()
        assertEquals(text(), html(root.children))
        val alice = users().single()
        assertEquals("users.a", alice.attributes["id"])

        // a status: the class attribute and the status text, nothing else; the <li> is the same node
        frame("status", """{"user":"a","status":"AWAY"}""")
        assertEquals(text(), html(root.children))
        assertSame(alice, users().single())
        assertEquals(2, tree.log.size, tree.log.joinToString("\n"))
        assertTrue(tree.log.any { it.startsWith("setAttribute") && "user AWAY" in it }, tree.log.joinToString("\n"))
        assertTrue(tree.log.any { it.startsWith("setText") && "AWAY" in it }, tree.log.joinToString("\n"))

        // a join: one item built after alice's, the count, the #@when block appears
        frame("join", """{"user":{"id":"b","name":"bob","status":"ONLINE"},"first":true,"count":2}""")
        assertEquals(text(), html(root.children))
        assertSame(alice, users()[0])
        assertEquals("users.b", users()[1].attributes["id"])
        assertTrue(tree.log.none { it.startsWith("remove") }, tree.log.joinToString("\n"))

        // a chat line
        frame("chat", """{"text":"hi all","seq":1,"from":"b","author":{"id":"b","name":"bob"},"timestamp":"t"}""")
        assertEquals(text(), html(root.children))
        assertTrue(html(root.children).contains("<li>bob: hi all</li>"))

        // alice leaves: her item and the company paragraph go, bob's node stays
        val bob = users()[1]
        frame("leave", """{"user":{"id":"a","name":"alice","status":"AWAY"},"last":true,"count":1}""")
        assertEquals(text(), html(root.children))
        assertSame(bob, users().single())
        assertTrue(tree.log.count { it.startsWith("remove") } >= 1, tree.log.joinToString("\n"))
        assertTrue(tree.log.none { it.startsWith("element") }, tree.log.joinToString("\n"))
    }
}
