package com.republicate.kroom.examples.chifoumi

import com.republicate.kroom.client.Room
import com.republicate.kroom.event.State
import com.republicate.kroom.examples.chifoumi.game.FoldTest
import com.republicate.kroom.examples.chifoumi.game.LobbyLogic
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
        val room = Room("chifoumi-lobby", LobbyState(), LobbyLogic.registry, engine)
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

    private fun DElement.byId(id: String): DElement? = descendants().filterIsInstance<DElement>().firstOrNull { it.attributes["id"] == id }

    @Test fun `the game's regions over the match`() {
        val match = frames[3].getObject("data")!!.getObject("match")!!
        val snapshot = Json.MutableObject(frames[0]).also { it["data"] = Json.MutableObject(frames[0].getObject("data")!!).also { d -> d["matches"] = Json.MutableObject(match.getString("id")!! to match) } }
        val room = Room("chifoumi-lobby", LobbyState(), LobbyLogic.registry, engine)
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
}
