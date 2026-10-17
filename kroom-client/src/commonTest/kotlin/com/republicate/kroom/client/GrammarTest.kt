package com.republicate.kroom.client

import com.republicate.kroom.event.Event
import com.republicate.kroom.event.Registry
import com.republicate.kroom.event.State
import com.republicate.kson.Json
import kotlin.test.*

/** Pins the binder's grammar: paths, the show forms, the markup scan and its check against the declarations. */
class GrammarTest {

    class Play(json: Json.MutableObject = Json.MutableObject()) : Event("play", json = json)

    class Lobby(json: Json.MutableObject = Json.MutableObject()) : State(json) {
        var queue by list<String>()
        val stats by obj()
        override fun reduce(frame: Event) {}
    }

    private val registry = Registry(::Play)

    private val snapshot = Json.parse(
        """{"queue":["b"],"stats":{"played":3,"rock":0},"matches":{"m1":{"id":"m1","finished":false}},"history":[],"users":[{"id":"a"},{"id":"b"}],"me":{"id":"b","name":"Bob"}}"""
    ) as Json.Object

    private val anonymous = Json.parse("""{"queue":["b"],"me2":{"id":"b"}}""") as Json.Object

    private fun at(path: String) = Path.parse(path).resolve(snapshot)
    private fun show(text: String, json: Json.Object = snapshot) = Show.parse(text).test(json)

    // Path

    @Test fun `a path resolves keys, indices and a last length`() {
        assertEquals(3, (at("stats.played") as Number).toInt())   // parsed integers may be Long
        assertEquals("Bob", at("me.name"))
        assertEquals(2, at("users.length"))
        assertEquals(0, at("history.length"))
        assertEquals(1, at("matches.length"))
        assertEquals("a", at("users.0.id"))
        assertEquals("b", at("@me"))
        assertTrue(Path.parse("@me").isMe)
    }

    @Test fun `a missing key or a path through one resolves to null`() {
        assertNull(at("nope"))
        assertNull(at("nope.deeper.still"))
        assertNull(at("users.7.id"))
        assertNull(Path.parse("stats.played").resolve(null))
    }

    @Test fun `a path is never empty nor holed and its head is its first key`() {
        assertFailsWith<IllegalArgumentException> { Path.parse("") }
        assertFailsWith<IllegalArgumentException> { Path.parse("   ") }
        assertFailsWith<IllegalArgumentException> { Path.parse("a..b") }
        assertEquals("stats", Path.parse("stats.played").head)
        assertEquals("stats.played", Path.parse(" stats.played ").toString())
    }

    @Test fun `truthy is present, not false, not zero, not empty`() {
        val empties = listOf(null, false, 0, 0L, 0.0, "", Json.parse("[]"), Json.parse("{}"))
        val fulls = listOf(true, 1, "x", Json.parse("[1]"), Json.parse("""{"k":1}"""))
        empties.forEach { assertFalse(Path.truthy(it), "truthy($it)") }
        fulls.forEach { assertTrue(Path.truthy(it), "truthy($it)") }
    }

    // Show

    @Test fun `show tests each form against the snapshot`() {
        val cases = listOf(
            "queue" to true,                       // truthy
            "history" to false,                    // empty
            "!history" to true,
            "stats.played=3" to true,
            "stats.played!=3" to false,
            "me.id=@me" to true,
            "queue∋@me" to true,
            "queue∋a" to false,
            "!queue∋@me" to false,
            "matches∋m1" to true,                  // an object keyed by it
            "me.name∋Bo" to true,                  // a string containing it
            "matches.m1.finished=false" to true,
            "nothing=1" to false,                  // missing path
            "nothing!=1" to true                   // not equal to what is absent
        )
        cases.forEach { (text, expected) -> assertEquals(expected, show(text), text) }
    }

    @Test fun `without me in the snapshot @me holds nothing`() {
        assertFalse(show("queue∋@me", anonymous))
        assertFalse(show("me.id=@me", anonymous))
        assertNull(Path.parse("@me").resolve(anonymous))
    }

    @Test fun `show parsing`() {
        assertFailsWith<IllegalArgumentException> { Show.parse("a=") }
        assertFailsWith<IllegalArgumentException> { Show.parse("a∋") }
        assertFailsWith<IllegalArgumentException> { Show.parse("") }
        val neg = Show.parse(" ! queue ")
        assertTrue(neg.negated)
        assertEquals(Show.Op.TRUTHY, neg.op)
        assertEquals("queue", neg.path.toString())
        val ne = Show.parse("stats.played!=3")
        assertFalse(ne.negated)
        assertEquals(Show.Op.NE, ne.op)
        assertEquals("stats.played", ne.path.toString())
        assertEquals("3", ne.literal)
        
        // token sits at index 0 (skipped by `i > 0`), then EQ matches at index 1 and Path.parse("!")
        // accepts "!" as a key (Path.parse checks only empty segments and "@me"), so the result is
        // `path "!" EQ "x"` instead of an IllegalArgumentException.
        assertFailsWith<IllegalArgumentException> { Show.parse("!=x") }
    }

    // Bindings.scan

    private val page = """
        <body data-k-room="r">
          <span data-k-field="users.length"></span>
          <div data-k-show="!queue∋@me" data-intent="play"></div>
          <ul data-k-each="history"><template><li data-k-field="text"></li></template></ul>
          <p data-k-on="round" data-k-for="2000"></p>
          <a data-k-show="match" data-k-follow href="x">go</a>
        </body>
    """.trimIndent()

    @Test fun `scan lists every data-k attribute in document order`() {
        val bs = Bindings.scan(page)
        assertEquals(
            listOf(
                Binding(Attr.ROOM, "r", false),
                Binding(Attr.FIELD, "users.length", false),
                Binding(Attr.SHOW, "!queue∋@me", false),
                Binding(Attr.EACH, "history", false),
                Binding(Attr.FIELD, "text", true),
                Binding(Attr.ON, "round", false),
                Binding(Attr.FOR, "2000", false),
                Binding(Attr.SHOW, "match", false),
                Binding(Attr.FOLLOW, "", false)
            ),
            bs
        )
        assertEquals(listOf(4), bs.withIndex().filter { it.value.inTemplate }.map { it.index })
        assertEquals("", bs.last().value)                     // a bare attribute
        assertEquals("queue", bs[2].path.toString())
        assertNull(bs[0].path)
    }

    // Bindings.check

    private fun check(html: String) = Bindings.check(html, registry, Lobby())

    @Test fun `valid markup has no problem`() {
        assertEquals(emptyList(), check("""
            <body data-k-room="r">
              <span data-k-field="stats.played"></span>
              <div data-k-show="!queue∋@me"></div>
              <ul data-k-each="queue"><template><li data-k-field="text"></li></template></ul>
              <p data-k-on="play" data-k-for="1500"></p>
              <p data-k-on="join"></p>
              <a data-k-show="queue" data-k-follow href="x"></a>
            </body>
        """))
    }

    @Test fun `room keys and @me are known`() {
        assertEquals(emptyList(), check("""<span data-k-field="users.length"></span><i data-k-show="me.id=@me"></i><b data-k-show="@me"></b><u data-k-field="pending"></u>"""))
    }

    @Test fun `a template's paths are relative and not checked`() {
        assertEquals(emptyList(), check("""<ul data-k-each="queue"><template><li data-k-field="text"></li></template></ul>"""))
    }

    @Test fun `check names what the room does not declare`() {
        val field = check("""<span data-k-field="nope.x"></span>""")
        assertEquals(1, field.size)
        assertTrue("nope" in field.single(), field.single())
        val on = check("""<p data-k-on="nope"></p>""")
        assertEquals(1, on.size)
        assertTrue("no such event" in on.single(), on.single())
        val each = check("""<ul data-k-each="nope"></ul>""")
        assertEquals(1, each.size)
        val forNaN = check("""<p data-k-on="play" data-k-for="soon"></p>""")
        assertEquals(1, forNaN.size)
        assertTrue("not a number" in forNaN.single(), forNaN.single())
        val bad = check("""<i data-k-show="a="></i>""")
        assertEquals(1, bad.size)
        assertTrue("bad show" in bad.single(), bad.single())   // the parse error surfaced
    }
}
