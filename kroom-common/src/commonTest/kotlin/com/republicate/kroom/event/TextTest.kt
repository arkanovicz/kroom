package com.republicate.kroom.event

import com.republicate.kson.Json
import kotlin.test.*

/** Pins a text several people write: one writer per part, a take whole or nothing, a write in place, a leave gives back. */
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
    private fun release(vararg ids: String) = Release(ids.toList())
    private fun write(ids: List<String>, texts: List<String>) =
        Write(Json.MutableObject("ids" to Json.MutableArray(ids), "texts" to Json.MutableArray(texts)))

    /** A text of these parts, ids "1".."n", all free. */
    private fun text(vararg texts: String) = TextState().also { s ->
        assertNull(s.act(a1, take("1")))
        assertNull(s.act(a1, write(listOf("1"), texts.toList())))
        assertNull(s.act(a1, release(*s.parts.map { it.id }.toTypedArray())))
    }

    private fun ids(s: TextState) = s.parts.map { it.id }
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

    @Test fun `a take signs the parts whole or not at all`() {
        val s = text("A", "B", "C")
        assertNull(s.act(a1, take("1", "2")))
        assertEquals(listOf("a/t1", "a/t1", null), holders(s))
        assertEquals("held {\"id\":\"2\",\"by\":\"a\"}", s.act(b, take("3", "2")).toString())
        assertEquals(listOf("a/t1", "a/t1", null), holders(s))   // 3 was free, still not taken
        assertEquals("held {\"id\":\"1\",\"by\":\"a\"}", s.act(a2, take("1")).toString())   // a tab holds, not a user
        assertEquals("gone {\"id\":\"9\"}", s.act(b, take("9")).toString())
        assertEquals("invalid {\"field\":\"ids\"}", s.act(b, take()).toString())
        assertEquals("invalid {\"field\":\"ids\"}", s.act(b, Take(Json.MutableObject("ids" to Json.MutableArray(listOf(1))))).toString())
        assertNull(s.act(a1, take("1", "3")))   // one's own again, and more
        assertEquals(listOf("a/t1", "a/t1", "a/t1"), holders(s))
    }

    @Test fun `a write one for one changes the text and keeps the id`() {
        val s = text("A", "B", "C")
        s.act(a1, take("2"))
        val w = write(listOf("2"), listOf("B2"))
        assertNull(s.act(a1, w))
        assertEquals(listOf("1", "2", "3"), ids(s))
        assertEquals("A\n\nB2\n\nC", s.text)
        assertEquals(3L, s.last)
        assertNull(w.born)
    }

    @Test fun `a write of one into three bears two parts in place held by the writer`() {
        val s = text("A", "B", "C")
        s.act(a1, take("2"))
        val w = write(listOf("2"), listOf("x", "y", "z"))
        assertNull(s.act(a1, w))
        assertEquals(listOf("4", "5"), w.born!!.map { it as String })
        assertEquals(listOf("1", "2", "4", "5", "3"), ids(s))
        assertEquals("A\n\nx\n\ny\n\nz\n\nC", s.text)
        assertEquals(listOf(null, "a/t1", "a/t1", "a/t1", null), holders(s))
        assertEquals(5L, s.last)
    }

    @Test fun `a write of three into one lets the extra parts go`() {
        val s = text("A", "B", "C", "D")
        s.act(a1, take("1", "2", "3"))
        assertNull(s.act(a1, write(listOf("1", "2", "3"), listOf("all"))))
        assertEquals(listOf("1", "4"), ids(s))
        assertEquals("all\n\nD", s.text)
        assertEquals(4L, s.last)   // ids are never reused
    }

    @Test fun `a write is refused on what one does not hold out of order or saying nothing and changes nothing`() {
        val s = text("A", "B", "C", "D")
        s.act(a1, take("1", "3"))
        s.act(b, take("2"))
        fun refused(expected: String, actor: Actor, w: Write) {
            val before = s.json.toString()
            assertEquals(expected, s.act(actor, w).toString())
            assertEquals(before, s.json.toString())
        }
        refused("not_held {\"id\":\"4\"}", a1, write(listOf("4"), listOf("x")))           // free
        refused("not_held {\"id\":\"2\"}", a1, write(listOf("2"), listOf("x")))           // another's
        refused("not_held {\"id\":\"1\"}", a2, write(listOf("1"), listOf("x")))           // one's other tab's
        refused("gone {\"id\":\"9\"}", a1, write(listOf("9"), listOf("x")))
        refused("scattered", a1, write(listOf("1", "3"), listOf("x", "y")))
        s.act(b, release("2"))
        s.act(a1, take("2"))
        refused("scattered", a1, write(listOf("2", "1"), listOf("x", "y")))              // adjacent, reversed
        refused("empty", a1, write(listOf("1"), listOf()))
        refused("invalid {\"field\":\"ids\"}", a1, write(listOf(), listOf("x")))
    }

    @Test fun `a release frees the parts and a blank one goes unless it is the last`() {
        val s = text("A", "B", "C")
        s.act(a1, take("1", "2"))
        assertNull(s.act(a1, release("1")))
        assertEquals("""{"id":"1","text":"A"}""", s.parts[0].json.toString())   // holder and client gone, not null
        assertEquals("not_held {\"id\":\"1\"}", s.act(a1, release("1")).toString())   // free
        assertEquals("not_held {\"id\":\"2\"}", s.act(b, release("2")).toString())    // another's
        assertNull(s.act(a1, write(listOf("2"), listOf(""))))
        assertNull(s.act(a1, release("2")))
        assertEquals(listOf("1", "3"), ids(s))
        val one = TextState()
        one.act(a1, take("1"))
        assertNull(one.act(a1, release("1")))
        assertEquals(listOf("1"), ids(one))   // a text is never without a part
        assertTrue(one.parts[0].free)
    }

    @Test fun `one idle the other replaces everything`() {
        val s = TextState()
        s.act(a1, take("1"))
        s.act(a1, write(listOf("1"), listOf("p", "q")))
        s.act(a1, release("1", "2"))
        assertNull(s.act(b, take("1", "2")))
        assertNull(s.act(b, write(listOf("1", "2"), listOf("one", "two"))))
        assertEquals("one\n\ntwo", s.text)

        val t = TextState()
        t.act(a1, take("1"))
        t.act(a1, write(listOf("1"), listOf("p", "q")))
        t.act(a1, release("1"))   // still holds 2
        assertEquals("held {\"id\":\"2\",\"by\":\"a\"}", t.act(b, take("1", "2")).toString())
        assertTrue(t.parts.none { it.holder == "b" })
    }

    @Test fun `a write after the part changed hands is not held`() {
        val s = TextState()
        s.act(a1, take("1"))
        s.act(a1, release("1"))
        s.act(b, take("1"))
        s.act(b, write(listOf("1"), listOf("b's")))
        assertEquals("not_held {\"id\":\"1\"}", s.act(a1, write(listOf("1"), listOf("late"))).toString())
        assertEquals("b's", s.text)
    }

    @Test fun `a leave releases what that tab held and only that`() {
        val s = text("A", "B", "C")
        s.act(a1, take("1", "3"))
        s.act(a2, take("2"))
        val out = logic.decide(s, a1, Leave())
        assertIs<Emit<Event>>(out)
        val r = out.events.single() as Release
        assertEquals(listOf("1", "3"), r.ids.map { it as String })
        s.reduce(r)
        assertEquals(listOf(null, "a/t2", null), holders(s))
        assertSame(done, logic.decide(s, b, Leave()))
    }

    @Test fun `a write marks only the parts whose text changed`() {
        val s = text("A", "B", "C")
        s.act(a1, take("1", "2", "3"))
        s.dirty = HashSet()
        assertNull(s.act(a1, write(listOf("1", "2", "3"), listOf("A", "B2", "C"))))
        assertEquals("2", s.dirty!!.map { it.toString() }.sorted().joinToString(" "))
    }

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
