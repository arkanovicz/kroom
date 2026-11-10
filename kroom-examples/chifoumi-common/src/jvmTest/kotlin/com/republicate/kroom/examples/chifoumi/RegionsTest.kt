package com.republicate.kroom.examples.chifoumi

import com.republicate.kroom.client.Room
import com.republicate.kroom.event.State
import com.republicate.kroom.examples.chifoumi.game.FoldTest
import com.republicate.kroom.examples.chifoumi.game.LobbyEvents
import com.republicate.kroom.examples.chifoumi.game.LobbyState
import com.republicate.kson.Json
import org.apache.velocity.engine.Config
import org.apache.velocity.engine.VelocityContext
import org.apache.velocity.engine.VelocityEngine
import org.apache.velocity.engine.resource.StockResourceLoader
import velocity.templates.VelocityTemplates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The pages' regions over the recorded match (bob's tab), merged by [Room] into a tree double: after every frame
 * each region's tree reads as the text render of the same roots (escaped by the engine, the sink raw), and what did
 * not change keeps its node. The browser's DOM run is `pages.mjs`.
 */
class RegionsTest {
    private val engine = VelocityEngine(null, Config(informalNavigation = true, referenceInsertionEventClasses = listOf("org.apache.velocity.engine.sink.DynamicEscaper")))
        .apply { addResourceLoader("stock", StockResourceLoader(VelocityTemplates::resolve)) }

    private val frames = FoldTest::class.java.getResource("/frames.jsonl")!!.readText().lines().filter { it.isNotBlank() }
        .map { Json.parse(it) as Json.Object }.filter { it.getString("event") != "final" }

    private val say: Say = { key, vars -> (listOf(key) + vars.values.map { "$it" }).joinToString(" ") }

    private class Region(val template: String, val root: DElement)

    /** The room's regions, each checked against its text render after every frame. */
    private fun replay(room: Room<LobbyState>, page: State, vararg templates: String, frames: List<Json.Object>, after: (Int, List<DElement>) -> Unit = { _, _ -> }) {
        val tree = TreeDouble()
        val regions = templates.map { Region(it, tree.element("div")) }
        room.page = page
        regions.forEach { room.attach("en/${it.template}", tree, it.root) }
        frames.forEachIndexed { i, f ->
            room.frame(f.getString("event")!!, f.getObject("data").toString())
            for (r in regions) {
                val text = engine.getTemplate("en/${r.template}").merge(VelocityContext(mutableMapOf("state" to room.state, "me" to room.me, "page" to page)))
                assertEquals(text, html(r.root.children), "frame $i (${f.getString("event")}), ${r.template}: the tree is the text render")
            }
            after(i, regions.map { it.root })
        }
    }

    @Test fun `the lobby's regions over the match`() {
        val room = Room("chifoumi-lobby", LobbyState(), LobbyEvents, engine)
        val page = LobbyPage(room, say) { "t" }
        var stat: DElement? = null
        var line: DElement? = null
        replay(room, page, "online.html", "lobby.html", frames = frames) { i, roots ->
            val lobby = roots[1]
            if (i == 0) stat = lobby.byId("stat-matches")
            if (lobby.byId("chat-log")!!.children.any { it is DElement }) line = line ?: lobby.byId("chat-log")!!.children.filterIsInstance<DElement>().single()
            assertSame(stat, lobby.byId("stat-matches"), "frame $i: the counter keeps its node")
            line?.let { assertSame(it, lobby.byId("chat-log")!!.children.filterIsInstance<DElement>().single(), "frame $i: the chat line keeps its node") }
            // the joiner comes with its own join, after the snapshot
            if (i == 0) assertEquals("1", html(roots[0].children).trim())
            if (i == 1) assertEquals("2", html(roots[0].children).trim())
            if (i == frames.lastIndex) html(lobby.children).let { assertTrue("Ready to play, <span id=\"player-display\">Bob</span>" in it && "Alice: gl hf" in it && "<strong id=\"stat-matches\">1</strong>" in it, it) }
        }
        assertTrue(line != null, "the chat line came")
    }

    /** The merge is targeted: a frame writing one line of the chat renders that item's body, the others are clean. */
    @Test fun `a frame writing one line renders one body`() {
        val room = Room("chifoumi-lobby", LobbyState(), LobbyEvents, engine)
        val tree = TreeDouble()
        val root = tree.element("div")
        room.page = LobbyPage(room, say) { "t" }
        room.attach("en/lobby.html", tree, root)
        val line = { n: Int -> """{"text":"line $n","seq":$n,"author":{"id":"a","name":"Alice"}}""" }
        val n = room.state.max - 1
        room.frame("state", """{"last":$n,"stats":{"played":0,"rock":0,"paper":0,"scissors":0,"well":0},"users":{},"history":[${(1..n).joinToString(",", transform = line)}]}""")
        tree.clearLog()
        room.frame("chat", line(n + 1))
        val sites = tree.reads
        assertTrue(sites < 2 * n, "$sites sites read for one line of ${n + 1}")   // a line's body reads two
        assertEquals(engine.getTemplate("en/lobby.html").merge(VelocityContext(mutableMapOf("state" to room.state, "me" to room.me, "page" to room.page))), html(root.children))
        // the page's own pass renders every item
        tree.clearLog()
        room.merge()
        assertTrue(tree.reads >= 2 * n, "${tree.reads} sites read by a whole pass")
    }

    /** A region is merged when a root its header declares changed: `online.html` reads the state, `lobby.html` the state and the page. */
    @Test fun `a region none of whose roots changed is not merged`() {
        val room = Room("chifoumi-lobby", LobbyState(), LobbyEvents, engine)
        room.page = LobbyPage(room, say) { "t" }
        val online = TreeDouble(); val onlineRoot = online.element("div")
        val lobby = TreeDouble(); val lobbyRoot = lobby.element("div")
        room.attach("en/online.html", online, onlineRoot).attach("en/lobby.html", lobby, lobbyRoot)
        room.frame("state", frames[0].getObject("data").toString())
        fun TreeDouble.work() = reads + log.size   // a merge reads its sites, a first pass writes them
        assertTrue(online.work() > 0 && lobby.work() > 0, "the snapshot merges every region")
        fun after(name: String, data: String): Pair<Int, Int> { online.clearLog(); lobby.clearLog(); room.frame(name, data); return online.work() to lobby.work() }
        after("status", """{"status":"IDLE","user":"nobody"}""").let { (o, l) -> assertEquals(0 to 0, o to l, "nothing changed: no region merged") }
        after("leave", """{"user":{"id":"nobody","name":"Nobody"},"last":true,"count":1}""").let { (o, l) -> assertTrue(o == 0 && l > 0, "the page took a line, the state had nothing: lobby $l, online $o") }
        after("chat", """{"text":"hi","seq":1,"author":{"id":"a","name":"Alice"}}""").let { (o, l) -> assertTrue(o > 0 && l > 0, "the state changed: both merged") }
        assertEquals(engine.getTemplate("en/lobby.html").merge(VelocityContext(mutableMapOf("state" to room.state, "me" to room.me, "page" to room.page))), html(lobbyRoot.children))
    }

    private fun DElement.byId(id: String): DElement? = descendants().filterIsInstance<DElement>().firstOrNull { it.attributes["id"] == id }

    @Test fun `the game's regions over the match`() {
        val match = frames[3].getObject("data")!!.getObject("match")!!
        val snapshot = Json.MutableObject(frames[0]).also { it["data"] = Json.MutableObject(frames[0].getObject("data")!!).also { d -> d["matches"] = Json.MutableObject(match.getString("id")!! to match) } }
        val room = Room("chifoumi-lobby", LobbyState(), LobbyEvents, engine)
        var pending: (() -> Unit)? = null
        val page = GamePage(room, say, verdict = { "verdict $it" }, later = { _, fn -> pending = fn; { pending = null } })
        val shown = ArrayList<String>()
        replay(room, page, "round.html", "match.html", frames = listOf(snapshot) + frames.drop(5)) { _, roots ->
            shown += html(roots[1].children)
            pending?.let { pending = null; it() }   // the reveal's two seconds pass before the next frame
        }
        assertTrue(shown.first().let { "you" in it && "choose" in it && "move-buttons" in it }, shown.first())
        assertTrue(shown.any { "verdict paper-rock" in it }, "a reveal with its verdict")
        assertTrue(shown.last().let { "match-result" in it && "class=\"winner\"" in it }, shown.last())
    }

    /** A fault is its region's alone: marked, reported, the others merged; the region's next merge builds it again. */
    @Test fun `a region that faults is marked broken, the others merge, and its next merge builds it again`() {
        val match = frames[3].getObject("data")!!.getObject("match")!!
        val snapshot = Json.MutableObject(frames[0].getObject("data")!!).also { it["matches"] = Json.MutableObject(match.getString("id")!! to match) }
        val room = Room("chifoumi-lobby", LobbyState(), LobbyEvents, engine)
        var wordless = false
        val page = GamePage(room, { key, vars -> if (wordless) error("no words") else say(key, vars) }, verdict = { "verdict $it" }, later = { _, _ -> {} })
        room.page = page
        val tree = TreeDouble()
        val game = tree.element("div"); val round = tree.element("div")
        room.attach("en/match.html", tree, game).attach("en/round.html", tree, round)
        val faults = ArrayList<Pair<String, Throwable>>()
        room.onFault = { template, fault -> faults += template to fault }
        fun text(template: String) = engine.getTemplate("en/$template").merge(VelocityContext(mutableMapOf("state" to room.state, "me" to room.me, "page" to page)))

        room.frame("state", snapshot.toString())
        assertEquals(text("match.html"), html(game.children))
        assertTrue(faults.isEmpty() && Room.BROKEN !in game.attributes)

        wordless = true
        room.merge()
        assertEquals(listOf("en/match.html"), faults.map { it.first }, "the page's words failed the one region that asks for them")
        assertTrue(generateSequence(faults.single().second) { it.cause }.any { it.message?.contains("no words") == true }, "${faults.single().second}")
        assertEquals("", game.attributes[Room.BROKEN], "its root says so")
        assertTrue(Room.BROKEN !in round.attributes)
        assertEquals(text("round.html"), html(round.children), "the region after it merged")

        wordless = false
        room.merge()
        assertEquals(1, faults.size)
        assertTrue(Room.BROKEN !in game.attributes, "mended, it says nothing")
        assertEquals(text("match.html"), html(game.children), "and it is the page again")
    }
}
