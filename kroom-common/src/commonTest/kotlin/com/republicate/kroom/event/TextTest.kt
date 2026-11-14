package com.republicate.kroom.event

import com.republicate.kson.Json
import kotlin.test.*

/** Pins a text several people write: parts taken as one, written whole, cut at the release; a late view is refused. */
class TextTest {

    private class Who(override val id: String, override val client: String) : Actor { override val name = id }

    private val a1 = Who("a", "t1")
    private val a2 = Who("a", "t2")   // the same user, another tab
    private val b = Who("b", "t3")

    private val logic = TextLogic<TextState>()

    /** What a room does with an intent: check, decide, fold the intent, then what it emits. The refusal, or null. */
    private fun TextState.act(actor: Actor, event: Event): Reason? {
        event.check(this, actor)?.let { return it }
        val out = logic.decide(this, actor, event)
        if (out is Reason) return out
        reduce(event)
        (out as Emit<*>).events.forEach { reduce(it) }
        return null
    }

    private fun take(vararg ids: String) = Take(Json.MutableObject("ids" to Json.MutableArray(ids.toList())))
    private fun after(id: String) = Take(Json.MutableObject("after" to id))
    private fun write(id: String, text: String) = Write(Json.MutableObject("id" to id, "text" to text))
    private fun write(id: String, text: String, caret: Int) = Write(Json.MutableObject("id" to id, "text" to text, "caret" to caret))
    private fun release(id: String) = Release(id)

    /** The refusal, and the state as it stood. */
    private fun TextState.refused(expected: String, actor: Actor, event: Event) {
        val before = json.toString()
        assertEquals(expected, act(actor, event).toString())
        assertEquals(before, json.toString())
    }

    private fun ids(s: TextState) = s.parts.map { it.id }
    private fun texts(s: TextState) = s.parts.map { it.text }
    private fun holders(s: TextState) = s.parts.map { it.holder?.let { h -> "$h/${it.client}" } }

    @Test fun `a text is born with one empty part and a snapshot reads the same`() {
        val s = TextState()
        assertEquals(listOf("1"), ids(s))
        assertEquals("", s.text)
        assertEquals(1L, s.last)
        val again = TextState(Json.parse(s.json.toString()) as Json.MutableObject)
        assertEquals(listOf("1"), ids(again))
        assertEquals(1L, again.last)
        assertEquals(s.json.toString(), again.json.toString())   // no second birth
    }

    @Test fun `a text as stored is cut again into free parts, the next one born after them`() {
        val stored = "A\n\nB\n\n```\nx\n\ny\n```"
        val s = TextState(stored)
        assertEquals(listOf("1", "2", "3"), ids(s))
        assertEquals(3L, s.last)
        assertTrue(s.parts.all { it.free })
        assertEquals(stored, s.text)
        assertNull(s.act(a1, take("3")))
        assertNull(s.act(a1, write("3", "c\n\nd")))
        assertNull(s.act(a1, release("3")))
        assertEquals(listOf("1", "2", "3", "4"), ids(s))
        assertEquals(4L, s.last)
        assertEquals(listOf("1"), ids(TextState("")))                                 // never without a part
        assertEquals(1L, TextState(" \n").last)
    }

    @Test fun `a take signs one part and is refused on what is gone held or scattered`() {
        val s = TextState("A\n\nB\n\nC")
        assertNull(s.act(a1, take("1")))
        assertEquals(listOf("a/t1", null, null), holders(s))
        s.refused("held {\"id\":\"1\",\"by\":\"a\"}", a2, take("1"))                   // a tab holds, not a user
        s.refused("held {\"id\":\"1\",\"by\":\"a\"}", b, take("1", "2"))
        s.refused("gone {\"id\":\"9\"}", b, take("2", "9"))
        s.refused("scattered", b, take("3", "2"))
        s.refused("invalid {\"field\":\"ids\"}", b, take())
        s.refused("invalid {\"field\":\"ids\"}", b, Take(Json.MutableObject("ids" to Json.MutableArray(listOf(1)))))
        s.refused("held {\"id\":\"1\",\"by\":\"a\"}", a1, take("1"))                  // its own: held, by itself
        s.refused("gone {\"id\":\"9\"}", a1, take("9"))                                 // refused, what it holds stays held
        s.refused("scattered", a1, take("3", "2"))
        s.act(a2, take("2"))
        s.refused("held {\"id\":\"2\",\"by\":\"a\"}", a1, take("2"))
    }

    @Test fun `a take elsewhere gives back what was held, cut`() {
        val s = TextState("X\n\nY")
        s.act(a1, take("1"))
        s.act(a1, write("1", "A\n\nB"))
        assertNull(s.act(a1, take("2")))
        assertEquals(listOf("1", "3", "2"), ids(s))
        assertEquals(listOf("A", "B", "Y"), texts(s))
        assertEquals(listOf(null, null, "a/t1"), holders(s))                            // one part per client, ever
        assertEquals(3L, s.last)
        assertEquals(1, s.parts.count { it.heldBy(a1) })
        assertEquals("""{"id":"1","text":"A"}""", s.parts[0].json.toString())

        val t = TextState("A\n\nB")
        t.act(a1, take("2"))
        t.act(a1, write("2", " "))
        assertNull(t.act(a1, take("1")))
        assertEquals(listOf("1"), ids(t))                                               // left blank, it goes
        assertEquals(listOf("a/t1"), holders(t))
    }

    @Test fun `a paragraph begun is a part born after, held`() {
        val free = TextState("A\n\nB")
        assertNull(free.act(a1, after("1")))
        assertEquals(listOf("1", "3", "2"), ids(free))
        assertEquals(listOf("A", "", "B"), texts(free))
        assertEquals(listOf(null, "a/t1", null), holders(free))
        assertEquals(3L, free.last)

        val own = TextState("A")
        own.act(a1, take("1"))
        own.act(a1, write("1", "A\n\nB"))
        assertNull(own.act(a1, after("1")))                                             // the new paragraph gesture
        assertEquals(listOf("1", "3", "2"), ids(own))                                   // the new part born first, the cut ones between
        assertEquals(listOf("A", "B", ""), texts(own))
        assertEquals(listOf(null, null, "a/t1"), holders(own))
        assertEquals(3L, own.last)

        val other = TextState("A\n\nB")
        other.act(b, take("1"))
        other.act(b, write("1", "b's"))
        assertNull(other.act(a1, after("1")))
        assertEquals(listOf("1", "3", "2"), ids(other))
        assertEquals(listOf("b's", "", "B"), texts(other))
        assertEquals(listOf("b/t3", "a/t1", null), holders(other))                      // their hold untouched

        other.refused("gone {\"id\":\"9\"}", a1, after("9"))
        other.refused("invalid {\"field\":\"ids\"}", a1, Take(Json.MutableObject("ids" to Json.MutableArray(listOf("2")), "after" to "1")))
        other.refused("invalid {\"field\":\"ids\"}", a1, Take(Json.MutableObject()))
    }

    @Test fun `a blank part left by a paragraph begun after it goes, the new one stays`() {
        val s = TextState("A\n\nB")
        s.act(a1, take("2"))
        s.act(a1, write("2", ""))
        assertNull(s.act(a1, after("2")))
        assertEquals(listOf("1", "3"), ids(s))
        assertEquals(listOf(null, "a/t1"), holders(s))
        assertEquals(3L, s.last)

        val empty = TextState()
        empty.act(a1, take("1"))
        assertNull(empty.act(a1, after("1")))
        assertEquals(listOf("2"), ids(empty))                                           // still never without a part
        assertEquals(listOf("a/t1"), holders(empty))
    }

    @Test fun `a caret is the holder's, said by each write and gone with the hold`() {
        val s = TextState("X\n\nY")
        s.act(a1, take("1"))
        assertNull(s.act(a1, write("1", "Hello", 3)))
        assertEquals(3, s.parts[0].caret)
        assertEquals("Hel", s.parts[0].head)
        assertEquals("lo", s.parts[0].tail)
        assertNull(s.act(a1, write("1", "Hello")))
        assertNull(s.parts[0].caret)                                                    // a write without it removes it
        assertEquals("Hello", s.parts[0].head)
        assertEquals("", s.parts[0].tail)
        assertNull(s.act(a1, write("1", "Hello", 99)))
        assertEquals("Hello", s.parts[0].head)                                          // clamped
        assertEquals("", s.parts[0].tail)
        assertNull(s.act(a1, write("1", "Hello", -1)))
        assertEquals("", s.parts[0].head)
        assertEquals("Hello", s.parts[0].tail)

        s.act(a1, write("1", "Hello", 2))
        assertNull(s.act(a1, release("1")))
        assertEquals("""{"id":"1","text":"Hello"}""", s.parts[0].json.toString())       // gone, not null

        s.act(a1, take("1"))
        s.act(a1, write("1", "Hello", 2))
        assertNull(s.act(a1, take("2")))                                                // a take elsewhere
        assertNull(s.parts["1"]!!.caret)

        s.act(a1, write("2", "Y", 1))
        s.reduce((logic.decide(s, a1, Leave()) as Emit<*>).events.single())             // the leave's release
        assertNull(s.parts["2"]!!.caret)
        assertTrue(s.parts.all { it.free })
    }

    @Test fun `a take of adjacent parts makes one, the first, saying what they all said`() {
        val s = TextState("A\n\nB\n\nC\n\nD")
        assertNull(s.act(a1, take("1", "2", "3")))
        assertEquals(listOf("1", "4"), ids(s))
        assertEquals(listOf("A\n\nB\n\nC", "D"), texts(s))
        assertEquals(listOf("a/t1", null), holders(s))
        assertEquals(4L, s.last)
        assertEquals("A\n\nB\n\nC\n\nD", s.text)

        val t = TextState("A\n\nB\n\nC\n\nD")
        t.refused("scattered", a1, take("3", "2", "1"))
        t.refused("scattered", a1, take("1", "3"))
    }

    @Test fun `a write is the part's text whole, kept as typed while held`() {
        val s = TextState("A\n\nB")
        s.act(a1, take("2"))
        assertNull(s.act(a1, write("2", "x\n\n\ny\n")))
        assertEquals(listOf("A", "x\n\n\ny\n"), texts(s))
        assertEquals(listOf("1", "2"), ids(s))
        assertEquals(2L, s.last)
        assertEquals(listOf(null, "a/t1"), holders(s))
        s.refused("not_held {\"id\":\"1\"}", a1, write("1", "z"))                       // free
        s.refused("not_held {\"id\":\"2\"}", a2, write("2", "z"))                       // one's other tab's
        s.refused("not_held {\"id\":\"2\"}", b, write("2", "z"))                        // another's
        s.refused("gone {\"id\":\"9\"}", a1, write("9", "z"))
    }

    @Test fun `a release cuts the part, the paragraphs born after it free`() {
        val s = TextState("X\n\nY\n\nZ")
        s.act(a1, take("2"))
        s.act(a1, write("2", "A\n\nB\n\nC"))
        assertNull(s.act(a1, release("2")))
        assertEquals(listOf("1", "2", "4", "5", "3"), ids(s))
        assertEquals(listOf("X", "A", "B", "C", "Z"), texts(s))
        assertTrue(s.parts.all { it.free })
        assertEquals(5L, s.last)
        assertEquals("""{"id":"2","text":"A"}""", s.parts[1].json.toString())          // holder and client gone, not null

        s.act(a1, take("4"))
        s.act(a1, write("4", "```\nx\n\ny\n```"))
        assertNull(s.act(a1, release("4")))
        assertEquals(listOf("1", "2", "4", "5", "3"), ids(s))                           // a fence is one part
        assertEquals(5L, s.last)

        s.act(b, take("1"))
        s.refused("not_held {\"id\":\"2\"}", a1, release("2"))                          // free
        s.refused("not_held {\"id\":\"1\"}", a1, release("1"))                          // another's
        s.refused("gone {\"id\":\"9\"}", a1, release("9"))
    }

    @Test fun `a part released blank goes unless it is the only one`() {
        val s = TextState("A\n\nB")
        s.act(a1, take("2"))
        s.act(a1, write("2", " \n\n"))
        assertEquals("A", s.text)                                                       // a blank part says nothing
        assertNull(s.act(a1, release("2")))
        assertEquals(listOf("1"), ids(s))
        assertEquals(2L, s.last)

        val one = TextState("A")
        one.act(a1, take("1"))
        one.act(a1, write("1", ""))
        assertNull(one.act(a1, release("1")))
        assertEquals(listOf("1"), ids(one))                                             // a text is never without a part
        assertEquals("", one.parts[0].text)
        assertTrue(one.parts[0].free)
    }

    @Test fun `select all and paste takes every free part as one`() {
        val s = TextState("A\n\nB\n\nC")
        assertNull(s.act(b, take("1", "2", "3")))
        assertNull(s.act(b, write("1", "P\n\nQ")))
        assertNull(s.act(b, release("1")))
        assertEquals("P\n\nQ", s.text)
        assertEquals(listOf("1", "4"), ids(s))
        assertEquals(4L, s.last)

        val t = TextState("A\n\nB\n\nC")
        t.act(a1, take("2"))
        t.refused("held {\"id\":\"2\",\"by\":\"a\"}", b, take("1", "2", "3"))
    }

    @Test fun `writes without a release in between change one part, nothing doubled`() {
        val s = TextState()
        s.act(a1, take("1"))
        assertNull(s.act(a1, write("1", "A\n\nB")))
        assertNull(s.act(a1, write("1", "A\n\nB\n\nC")))
        assertEquals(listOf("1"), ids(s))
        assertEquals("A\n\nB\n\nC", s.parts[0].text)
        assertNull(s.act(a1, release("1")))
        assertEquals(listOf("A", "B", "C"), texts(s))
        assertEquals(listOf("1", "2", "3"), ids(s))
    }

    @Test fun `a late view is refused, never misapplied`() {
        val s = TextState("A")
        s.act(a1, take("1"))
        s.act(a1, release("1"))
        s.act(b, take("1"))
        s.act(b, write("1", "b's"))
        s.refused("not_held {\"id\":\"1\"}", a1, write("1", "late"))
        assertEquals("b's", s.text)

        val t = TextState("A\n\nB")
        t.act(a1, take("2"))
        t.act(a1, release("2"))
        t.act(b, take("1", "2"))                                                        // merged away
        t.refused("gone {\"id\":\"2\"}", a1, write("2", "late"))
        assertEquals("A\n\nB", t.text)
    }

    @Test fun `two writers in two parts at once`() {
        val s = TextState("A\n\nB")
        s.act(a1, take("1"))
        s.act(b, take("2"))
        assertNull(s.act(a1, write("1", "a1")))
        assertNull(s.act(b, write("2", "b1")))
        assertNull(s.act(a1, write("1", "a2")))
        assertNull(s.act(b, write("2", "b2")))
        assertEquals(listOf("a2", "b2"), texts(s))
        assertEquals(listOf("a/t1", "b/t3"), holders(s))
    }

    @Test fun `a leave releases what that tab held and only that`() {
        val s = TextState("A\n\nB\n\nC")
        s.act(a1, take("1"))
        s.act(a2, take("3"))
        val out = logic.decide(s, a1, Leave())
        assertIs<Emit<Event>>(out)
        val r = out.events.single() as Release
        assertEquals("1", r.id)
        s.reduce(r)
        assertEquals(listOf(null, null, "a/t2"), holders(s))
        assertEquals(emptyList(), (logic.decide(s, b, Leave()) as Emit<*>).events)
    }

    // a room with more: a text only its editors write, until it closes
    private class Closing(json: Json.MutableObject = Json.MutableObject()) : TextState(json) {
        var editors by array()
            private set
        var closed by bool()
            private set
        constructor(text: String, vararg editors: String) : this(TextState(text).json) { this.editors = Json.MutableArray(editors.toList()) }
        override fun reduce(event: Event) { super.reduce(event); if (event is Close) closed = true }
    }
    private class Draft(json: Json.MutableObject = Json.MutableObject()) : Take(json) {
        init { check { state: Closing, actor -> if (state.closed == true) Reason("closed") else if (state.editors?.contains(actor.id) != true) Reason("not_editor") else null } }
    }
    private class Close(json: Json.MutableObject = Json.MutableObject()) : Event("close", json = json)
    private object ClosingEvents : TextEvents(::Draft) { val close = intent(::Close) }
    private object ClosingLogic : TextLogic<Closing>(ClosingEvents) {
        init { on(ClosingEvents.close) { state, _, _ -> Emit(state.parts.filter { !it.free }.map { Release(it.id) }) } }
    }

    @Test fun `a room's own take says who may write, a text that closes gives back what was held`() {
        val s = Closing("A\n\nB", "a")
        fun act(actor: Actor, name: String, json: Json.MutableObject = Json.MutableObject()): Reason? {
            val event = ClosingEvents.of(name, json)!!
            event.check(s, actor)?.let { return it }
            val out = ClosingLogic.decide(s, actor, event)
            if (out is Reason) return out
            s.reduce(event)
            (out as Emit<*>).events.forEach { s.reduce(it) }
            return null
        }
        fun take(id: String) = Json.MutableObject("ids" to Json.MutableArray(listOf(id)))
        assertEquals("not_editor", act(b, "take", take("1")).toString())
        assertNull(act(a1, "take", take("1")))                                     // the entry builds the room's take: its check ran, the text's fold took it
        assertEquals("held {\"id\":\"1\",\"by\":\"a\"}", act(b, "take", take("1")).toString())   // the text's check first
        assertNull(act(a1, "write", Json.MutableObject("id" to "1", "text" to "A\n\nA2")))
        assertNull(act(b, "close"))                                                // someone else closes: the part is given back, cut, by the logic
        assertEquals(listOf(null, null, null), holders(s))
        assertEquals(listOf("A", "A2", "B"), texts(s))
        assertEquals("closed", act(a1, "take", take("1")).toString())
        assertEquals("not_held {\"id\":\"1\"}", act(a1, "write", Json.MutableObject("id" to "1", "text" to "late")).toString())
    }

    @Test fun `a write marks its part, a release that cuts the part and the born ones`() {
        val s = TextState("A\n\nB\n\nC")
        s.act(a1, take("2"))
        s.dirty = HashSet()
        assertNull(s.act(a1, write("2", "x\n\ny\n\nz")))
        assertEquals("2", dirty(s))
        s.dirty!!.clear()
        assertNull(s.act(a1, release("2")))
        assertEquals("2 4 5", dirty(s))
    }

    @Test fun `a paragraph begun after one's own part marks the part, the new one and the cut ones`() {
        val s = TextState("A")
        s.act(a1, take("1"))
        s.act(a1, write("1", "A\n\nB"))
        s.dirty = HashSet()
        assertNull(s.act(a1, after("1")))
        assertEquals("1 2 3", dirty(s))
    }

    private fun dirty(s: State) = s.dirty!!.map { it.toString() }.sorted().joinToString(" ")

    @Test fun `a text is cut at its blank lines a fenced block kept whole`() {
        assertEquals(listOf("a", "b"), Part.cut("a\n\nb"))
        assertEquals(listOf("a\nb", "c"), Part.cut("a\nb\n\n \n\nc"))   // several blank lines cut once
        assertEquals(listOf("```\nx\n\ny\n```", "z"), Part.cut("```\nx\n\ny\n```\n\nz"))
        assertEquals(listOf("~~~\nx\n\ny\n~~~"), Part.cut("~~~\nx\n\ny\n~~~"))
        assertEquals(listOf("```kotlin\nval x = 1\n\nval y = 2\n```", "z"), Part.cut("```kotlin\nval x = 1\n\nval y = 2\n```\n\nz"))
        assertEquals(listOf("```\nx\n\n````", "z"), Part.cut("```\nx\n\n````\n\nz"))   // a longer fence closes
        assertEquals(listOf("````\nx\n```\n\ny\n````", "z"), Part.cut("````\nx\n```\n\ny\n````\n\nz"))   // a shorter one does not
        assertEquals(listOf("```\nx\n~~~\n\ny\n```"), Part.cut("```\nx\n~~~\n\ny\n```"))   // nor another character
        assertEquals(listOf("a", "```\nx\n\ny"), Part.cut("a\n\n```\nx\n\ny"))   // unclosed: the rest
        assertEquals(emptyList(), Part.cut(""))
        assertEquals(emptyList(), Part.cut(" \n\n\t\n"))
        assertEquals(listOf("a", "```\nx\n\ny\n```"), Part.cut("a\r\n\r\n```\r\nx\r\n\r\ny\r\n```\r\n"))
    }

    @Test fun `cutting what was cut gives it back`() {
        listOf(
            listOf("a"),
            listOf("# Title", "a paragraph\non two lines", "- a\n- list"),
            listOf("intro", "```kotlin\nfun f() {\n\n    g()\n}\n```", "outro"),
            listOf("~~~~\n```\n\n```\n~~~~", "end")
        ).forEach { parts -> assertEquals(parts, Part.cut(parts.joinToString("\n\n"))) }
    }
}
