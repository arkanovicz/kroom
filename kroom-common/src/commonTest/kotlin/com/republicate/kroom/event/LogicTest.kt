package com.republicate.kroom.event

import com.republicate.kson.Json
import kotlin.test.*

/** Pins the contract: a transition is a function, the state is the fold, the registry is the alphabet. */
class LogicTest {

    class Set(json: Json.MutableObject = Json.MutableObject()) : Event("set", json = json) { var n by int().required.out }
    class Note(json: Json.MutableObject = Json.MutableObject()) : Event("note", json = json) {
        var author by string().out
        val text by string().required.visibleTo { it == author }
    }
    class Add(json: Json.MutableObject = Json.MutableObject()) : Event("add", fires = Fires(::Set), json = json) { val by by int().required }
    class Wait(json: Json.MutableObject = Json.MutableObject()) : Event("wait", fires = Fires(::Set), pending = true, json = json)

    class Deck(json: Json.MutableObject) : JsonView(json) {
        val seed by long().hidden
        val left by int()
    }

    class Counter(json: Json.MutableObject = Json.MutableObject()) : State(json) {
        var n by int()
            private set
        val notes by log(::Note, max = 2)
        override fun reduce(event: Event) {
            super.reduce(event)
            when (event) {
                is Set -> n = event.n
                is Note -> notes += event
                else -> {}
            }
        }
    }

    object Rule : Logic<Counter> {
        override val registry = Registry(::Add, ::Note, ::Wait)
        override fun fire(state: Counter, actor: Actor, intent: Event): Outcome<Event> = when (intent) {
            is Add -> add(state, intent)
            is Note -> done
            is Wait -> held()
            else -> Reason("unknown")
        }
        fun add(state: Counter, add: Add): Outcome<Set> {
            val n = (state.n ?: 0) + add.by
            return if (n > 10) Reason("too_big", Json.Object("max" to 10)) else fired(Set().also { it.n = n })
        }
    }

    private val alice = object : Actor { override val id = "alice"; override val name = "Alice"; override val client = "t1" }

    @Test fun `a transition is a function of the marking and the intent`() {
        val counter = Counter(Json.MutableObject("n" to 4))
        val out = Rule.fire(counter, alice, Add(Json.MutableObject("by" to 3)))
        assertIs<Fired<Event>>(out)
        assertEquals(7, (out.events.single() as Set).n)
        assertFalse(out.held)
        val refused = Rule.fire(counter, alice, Add(Json.MutableObject("by" to 9)))
        assertIs<Reason>(refused)
        assertEquals("too_big {\"max\":10}", refused.toString())
        assertEquals(4, counter.n) // a function: the marking is read, never written
        assertTrue((Rule.fire(counter, alice, Wait()) as Fired).held)
    }

    @Test fun `the state is the fold of the frames, bounded logs included`() {
        val counter = Counter()
        assertNull(counter.n)
        counter.reduce(Set(Json.MutableObject("n" to 7)))
        assertEquals(7, counter.n)
        listOf("a", "b", "c").forEach { counter.reduce(Note(Json.MutableObject("text" to it))) }
        assertEquals(listOf("b", "c"), counter.notes.map { it.text })
        assertEquals("""{"n":7,"notes":[{"text":"b"},{"text":"c"}]}""", counter.json.toString()) // the snapshot is the json
        assertEquals(listOf("users", "n", "notes"), counter.describe().map { (it as Json.Object).getString("name") })
    }

    @Test fun `the registry is the alphabet, postable names apart`() {
        val r = Rule.registry
        assertEquals(setOf("status", "cancel", "add", "note", "wait"), r.names)
        assertEquals(setOf("join", "leave", "status", "cancel", "add", "set", "note", "wait"), r.all)   // the room's own first: on every wire
        assertNull(r.of("set"))                      // nobody posts what is only fired
        assertNotNull(r.frame("set", Json.MutableObject("n" to 1)))
        val d = r.describe().map { it as Json.Object }.associateBy { it.getString("name")!! }
        assertEquals(false, d["set"]!!.getBoolean("postable"))
        assertEquals(listOf("set"), d["add"]!!.getArray("fires")!!.toList())
        assertEquals(true, d["wait"]!!.getBoolean("pending"))
        val n = d["set"]!!.getArray("fields")!!.map { it as Json.Object }.single()
        assertEquals(true, n.getBoolean("out"))
        assertEquals(listOf(false, true), d["note"]!!.getArray("fields")!!.map { (it as Json.Object).getBoolean("selective") })
    }

    @Test fun `an out field is the loop's, undeclared when sent and never missing`() {
        assertNull(Set().validate())
        assertEquals("undeclared {\"field\":\"n\"}", Set(Json.MutableObject("n" to 1)).validate().toString())
        assertFailsWith<IllegalStateException> { Set().n } // required: the loop must fill it before the wire
    }

    @Test fun `a selective field is seen by whom its rule names at any depth, to is for the named`() {
        val note = Note(Json.MutableObject("author" to "alice", "text" to "secret"))
        assertEquals("secret", note.text)   // the server reads it
        assertEquals("""{"author":"alice","text":"secret"}""", note.view("alice").toString())
        assertEquals("""{"author":"alice"}""", note.view("bob").toString())
        assertEquals("""{"author":"alice"}""", note.view(null).toString())   // nobody in particular
        val counter = Counter().also { it.reduce(note) }
        assertEquals("""{"notes":[{"author":"alice"}]}""", counter.view("bob").toString())   // through a log of views
        assertEquals("""{"notes":[{"author":"alice","text":"secret"}]}""", counter.view("alice").toString())
        assertEquals("secret", counter.notes.single().text)
        assertNull(Note(Json.MutableObject("author" to "alice")).text)   // absent from some views, so it reads nullable
        Set().let { assertSame(it.json, it.view("bob")) }   // nothing selective: the json itself, one frame for everyone
        Deck(Json.MutableObject("seed" to 42L, "left" to 52)).let {
            assertEquals(42L, it.seed)   // the server reads it
            assertEquals(listOf("""{"left":52}""", """{"left":52}"""), listOf(it.view("alice"), it.view(null)).map { v -> v.toString() })   // nobody sees it
        }
        val set: Set = Set().to("a", "b")   // keeps its type
        assertEquals(listOf("a", "b"), set.to)
        assertEquals(emptyList(), Set().to)
        assertEquals("""{}""", set.json.toString()) // the address is the object's, never the wire's
    }
}
