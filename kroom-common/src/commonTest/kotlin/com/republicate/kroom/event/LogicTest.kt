package com.republicate.kroom.event

import com.republicate.kson.Json
import kotlin.test.*

/** Pins the contract: the logic is a function, the State is the fold, a deposit is the State's, the room's events are the alphabet. */
class LogicTest {

    class Set(json: Json.MutableObject = Json.MutableObject()) : Event("set", json = json) { var n by int().required.out }
    class Note(json: Json.MutableObject = Json.MutableObject()) : Event("note", json = json) {
        var author by string().out
        val text by string().required.visibleTo { it == author }
    }
    open class Add(json: Json.MutableObject = Json.MutableObject()) : Event("add", json = json) {
        val by by int().required
        init { check { state: Counter, _ -> if (state.n == 10) Reason("full") else null } }
    }
    class AddOdd(json: Json.MutableObject = Json.MutableObject()) : Add(json) { init { check { _: Counter, _ -> if (by % 2 == 0) Reason("even") else null } } }
    class Wait(json: Json.MutableObject = Json.MutableObject()) : Event("wait", json = json) { var user by string().out }
    class Waiter(json: Json.MutableObject = Json.MutableObject()) : JsonView(json) { val id by string().required }

    class Deck(json: Json.MutableObject) : JsonView(json) {
        val seed by long().hidden
        val left by int()
    }

    class Counter(json: Json.MutableObject = Json.MutableObject()) : State(json) {
        var n by int()
            private set
        val notes by log(::Note, max = 2)
        val waiting by keyed(::Waiter)
        override fun reduce(event: Event) {
            super.reduce(event)
            when (event) {
                is Set -> n = event.n
                is Note -> notes += event
                is Wait -> waiting.add(Waiter(Json.MutableObject("id" to event.user)))
                is Cancel -> if (event.intent == "wait") event.user?.let { waiting.remove(it) }
                else -> {}
            }
        }
    }

    object Events : RoomEvents() {
        val add = intent(::Add)
        val note = intent(::Note)
        val wait = intent(::Wait, cancellable = true)
        val set = event(::Set)
    }

    object Rule : Logic<Counter>(Events) {
        init {
            on(Events.add) { state, _, add ->
                val n = (state.n ?: 0) + add.by
                if (n > 10) Reason("too_big", Json.Object("max" to 10)) else emit(Set().also { it.n = n })
            }
            on(Events.note) { _, _, _ -> done }
            on(Events.wait) { state, actor, wait -> if (actor.id in state.waiting) Reason("already_waiting") else done.also { wait.user = actor.id } }
            cancel(Events.wait) { state, actor, _ -> if (actor.id in state.waiting) done else Reason("nothing_to_cancel") }
        }
    }

    private val alice = object : Actor { override val id = "alice"; override val name = "Alice"; override val client = "t1" }

    @Test fun `the logic is a function of the State and the intent`() {
        val counter = Counter(Json.MutableObject("n" to 4))
        val out = Rule.decide(counter, alice, Add(Json.MutableObject("by" to 3)))
        assertIs<Emit<Event>>(out)
        assertEquals(7, (out.events.single() as Set).n)
        val refused = Rule.decide(counter, alice, Add(Json.MutableObject("by" to 9)))
        assertIs<Reason>(refused)
        assertEquals("too_big {\"max\":10}", refused.toString())
        assertEquals(4, counter.n) // a function: the State is read, never written
    }

    @Test fun `a handler per intent a cancel per cancellable one checked when first needed`() {
        val partial = object : Logic<Counter>(Events) { init { on(Events.add) { _, _, _ -> done } } }
        assertFailsWith<IllegalArgumentException> { partial.decide(Counter(), alice, Add(Json.MutableObject("by" to 1))) }   // note, wait unhandled
        assertFailsWith<IllegalArgumentException> { partial.checkCoverage() }
        assertFailsWith<IllegalArgumentException> { object : Logic<Counter>(Events) { init { cancel(Events.add) { _, _, _ -> done } } } }   // not cancellable
        assertFailsWith<IllegalArgumentException> { object : Logic<Counter>(Events) { init { on(ChatEvents().chat) { _, _, _ -> done } } } }   // another room's
        assertSame(done, Rule.decide(Counter(), alice, Join()))   // kroom's own, unhandled: nothing to do
        assertEquals("not_cancellable", (Rule.decide(Counter(), alice, Cancel(Json.MutableObject("intent" to "add"))) as Reason).key)
    }

    @Test fun `a check is declared on the State it reads and refuses before the logic`() {
        assertEquals(listOf(Counter::class), Add().checkStates)
        assertEquals(emptyList(), Set().checkStates)
        assertEquals("full", Add(Json.MutableObject("by" to 1)).check(Counter(Json.MutableObject("n" to 10)), alice)?.key)
        assertNull(Add(Json.MutableObject("by" to 1)).check(Counter(Json.MutableObject("n" to 9)), alice))
        assertNull(Set().check(Counter(), alice))   // no check, nothing refused
        assertFailsWith<IllegalStateException> { Add().check(ChatState(), alice) }   // another State
    }

    @Test fun `a subclass's check runs after its parent's and the first refusal wins`() {
        assertEquals(listOf(Counter::class, Counter::class), AddOdd().checkStates)
        assertEquals("full", AddOdd(Json.MutableObject("by" to 2)).check(Counter(Json.MutableObject("n" to 10)), alice)?.key)   // the parent's, first
        assertEquals("even", AddOdd(Json.MutableObject("by" to 2)).check(Counter(Json.MutableObject("n" to 9)), alice)?.key)
        assertNull(AddOdd(Json.MutableObject("by" to 1)).check(Counter(Json.MutableObject("n" to 9)), alice))
    }

    @Test fun `a deposit is the State's and cancel reads it there`() {
        val counter = Counter()
        val wait = Wait()
        assertSame(done, Rule.decide(counter, alice, wait))
        assertEquals("nothing_to_cancel", (Rule.decide(counter, alice, Cancel(Json.MutableObject("intent" to "wait"))) as Reason).key)   // not yet folded
        counter.reduce(wait)
        assertEquals(listOf("alice"), counter.waiting.map { it.id })
        assertEquals("already_waiting", (Rule.decide(counter, alice, Wait()) as Reason).key)
        val cancel = Cancel(Json.MutableObject("intent" to "wait"))
        assertSame(done, Rule.decide(counter, alice, cancel))
        cancel.user = "alice"   // the room's
        counter.reduce(cancel)
        assertTrue(counter.waiting.isEmpty())
    }

    @Test fun `the state is the fold of the frames bounded logs included`() {
        val counter = Counter()
        assertNull(counter.n)
        counter.reduce(Set(Json.MutableObject("n" to 7)))
        assertEquals(7, counter.n)
        listOf("a", "b", "c").forEach { counter.reduce(Note(Json.MutableObject("text" to it))) }
        assertEquals(listOf("b", "c"), counter.notes.map { it.text })
        assertEquals("""{"n":7,"notes":[{"text":"b"},{"text":"c"}]}""", counter.json.toString()) // the snapshot is the json
        assertEquals(listOf("users", "n", "notes", "waiting"), counter.describe().map { (it as Json.Object).getString("name") })
    }

    @Test fun `the room's events are the alphabet postable names apart`() {
        val r = Rule.events
        assertEquals(setOf("status", "cancel", "add", "note", "wait"), r.names)
        assertEquals(listOf("join", "leave", "status", "cancel", "add", "note", "wait", "set"), r.all.toList())   // the room's own first: on every wire
        assertNull(r.of("set"))                      // nobody posts what is only emitted
        assertNotNull(r.frame("set", Json.MutableObject("n" to 1)))
        val d = r.describe().map { it as Json.Object }.associateBy { it.getString("name")!! }
        assertEquals(false, d["set"]!!.getBoolean("postable"))
        assertEquals(true, d["wait"]!!.getBoolean("cancellable"))
        assertEquals(false, d["add"]!!.getBoolean("cancellable"))
        assertFailsWith<IllegalArgumentException> { object : RoomEvents() { val again = intent(::Status) } }   // declared twice
        val n = d["set"]!!.getArray("fields")!!.map { it as Json.Object }.single()
        assertEquals(true, n.getBoolean("out"))
        assertEquals(listOf(false, true), d["note"]!!.getArray("fields")!!.map { (it as Json.Object).getBoolean("selective") })
    }

    @Test fun `an out field is the loop's undeclared when sent and never missing`() {
        assertNull(Set().validate())
        assertEquals("undeclared {\"field\":\"n\"}", Set(Json.MutableObject("n" to 1)).validate().toString())
        assertFailsWith<IllegalStateException> { Set().n } // required: the loop must fill it before the wire
    }

    @Test fun `a selective field is seen by whom its rule names at any depth to is for the named`() {
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
