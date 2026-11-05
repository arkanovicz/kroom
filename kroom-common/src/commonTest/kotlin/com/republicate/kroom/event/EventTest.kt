package com.republicate.kroom.event

import com.republicate.kson.Json
import kotlin.test.*

/** Pins the Event contract: a typed view over JSON, a declaration as data, one life in order. */
class EventTest {

    enum class Move { ROCK, PAPER, SCISSORS }

    class Play(json: Json.MutableObject = Json.MutableObject()) : Event("play", permission = "game.play", json = json) {
        val move by enum<Move>().required
        var note by string()
    }

    class Match(json: Json.MutableObject = Json.MutableObject()) : Event("match", audience = Audience.USERS, json = json) {
        val player1 by string().required
        val player2 by string().required
        val round by int()
        val rated by bool()
    }

    @Test fun `the json is the truth the class a view`() {
        val play = Play(Json.MutableObject("move" to "rock"))   // a button's spelling
        assertEquals(Move.ROCK, play.move)
        assertNull(play.note)
        play.note = "gg"
        assertEquals("gg", play.json["note"])
        assertEquals("""{"move":"rock","note":"gg"}""", play.json.toString())   // the json is the truth, read whatever the case
    }

    @Test fun `a declaration is data`() {
        val d = Play().describe()
        assertEquals("play", d.getString("name"))
        assertEquals("ROOM", d.getString("audience"))
        assertEquals("game.play", d.getString("permission"))
        val fields = d.getArray("fields")!!.map { it as Json.Object }
        assertEquals(listOf("move", "note"), fields.map { it.getString("name") })
        assertEquals(listOf(true, false), fields.map { it.getBoolean("required") })
        assertEquals("STRING", fields[0].getString("kind"))
    }

    @Test fun `validation names the field accept refuses with it`() {
        assertEquals("missing {\"field\":\"move\"}", Play().validate().toString())
        assertEquals("invalid {\"field\":\"move\",\"kind\":\"STRING\"}", Play(Json.MutableObject("move" to "WELL")).validate().toString())
        assertEquals("invalid {\"field\":\"round\",\"kind\":\"INT\"}", Match(Json.MutableObject("player1" to "a", "player2" to "b", "round" to "x")).validate().toString())
        assertNull(Match(Json.MutableObject("player1" to "a", "player2" to "b")).validate())
        assertEquals("undeclared {\"field\":\"evil\"}", Play(Json.MutableObject("move" to "ROCK", "evil" to 1)).validate().toString())
        val e = assertFailsWith<Event.Rejected> { Play().accept("alice") }
        assertEquals("missing", e.reason.key)
        assertFailsWith<IllegalStateException> { Play().move }
    }

    @Test fun `a form's text is parsed in its kind an empty field null a bad text left for validation`() {
        val round = Match().fields.single { it.name == "round" }
        val move = Play().fields.single { it.name == "move" }
        assertEquals(3, round.parse("3"))
        assertNull(round.parse(""))   // the key stays: a field emptied on purpose
        assertEquals("x", round.parse("x"))
        assertEquals("", move.parse(""))   // a string is what it says
        val rated = Match().fields.single { it.name == "rated" }
        assertEquals(true, rated.parse("on"))   // a checkbox
        assertEquals(false, rated.parse("false"))
        assertNull(rated.parse(""))
        val json = Json.MutableObject("player1" to "a", "player2" to "b", "round" to round.parse(""))
        assertTrue(json.containsKey("round"))
        assertNull(Match(json).validate())
        assertEquals("invalid {\"field\":\"round\",\"kind\":\"INT\"}", Match(Json.MutableObject("player1" to "a", "player2" to "b", "round" to round.parse("x"))).validate().toString())
    }

    @Test fun `one life in order stamped in the metadata`() {
        val play = Play(Json.MutableObject("move" to "PAPER"))
        assertEquals(Stage.BORN, play.stage)
        play.accept("alice", "t1")
        assertEquals(Stage.ACCEPTED, play.stage)
        assertEquals("alice", play.meta.from)
        assertEquals("t1", play.meta.tab)
        play.apply()
        assertNotNull(play.meta.at)
        play.sequence(42)
        assertEquals(42L, play.meta.seq)
        assertEquals(Stage.APPLIED, play.stage) // the wire says the verdict
        assertFailsWith<IllegalStateException> { play.sequence(43) }
        play.persist()
        assertEquals(Stage.PERSISTED, play.stage)
        assertEquals("""{"move":"PAPER"}""", play.json.toString()) // the metadata is the object's, never the wire's
    }

    @Test fun `a step out of order is the object's error`() {
        val play = Play(Json.MutableObject("move" to "PAPER"))
        assertFailsWith<IllegalStateException> { play.sequence(1) }
        play.accept("alice").apply()
        assertFailsWith<IllegalStateException> { play.accept("bob") }
        assertFailsWith<IllegalStateException> { play.reject(Reason("late")) }
    }

    @Test fun `a rejection carries its reason and goes no further`() {
        val play = Play(Json.MutableObject("move" to "PAPER")).accept("alice")
        play.reject(Reason("not_your_turn", Json.Object("turn" to "bob")))
        assertEquals(Stage.REJECTED, play.stage)
        assertFailsWith<IllegalStateException> { play.sequence(7) }
        assertFalse(play.sequenced)
        assertFailsWith<IllegalStateException> { play.persist() }
        assertEquals("not_your_turn", play.meta.reason)
        assertEquals("bob", play.meta.args!!.getString("turn"))
    }

    @Test fun `a room-born event applies from birth`() {
        val match = Match(Json.MutableObject("player1" to "a", "player2" to "b")).apply().sequence(1)
        assertEquals(Stage.APPLIED, match.stage)
        assertFailsWith<IllegalStateException> { Match(Json.MutableObject("player1" to "a", "player2" to "b")).persist() }
        assertNull(match.meta.from)
    }

    @Test fun `a room's events rebuild an intent from a frame and describe the alphabet`() {
        val catalog = object : RoomEvents() { val play = intent(::Play); val match = intent(::Match) }
        assertEquals(setOf("status", "cancel", "play", "match"), catalog.names)   // kroom's two intents are postable in every room
        val e = catalog.of("play", """{"move":"SCISSORS"}""")
        assertIs<Play>(e)
        assertEquals(Stage.BORN, e.stage)
        assertEquals(Move.SCISSORS, e.move)
        assertNull(catalog.of("dance"))
        assertEquals(6, catalog.describe().size)   // join, leave, status, cancel, and the two
    }

    @Test fun `a client's metadata is dropped at birth`() {
        val json = Json.MutableObject("_" to Json.Object("from" to "x", "stage" to "APPLIED"), "move" to "ROCK")
        val play = Play(json)
        assertEquals(Stage.BORN, play.stage)
        assertNull(play.meta.from)
        assertNull(json["_"])
        assertNull(play.validate())
    }

    class Seat(json: Json.MutableObject = Json.MutableObject()) : JsonView(json) {
        val id by string().required
        val name by string()
    }

    class Table(json: Json.MutableObject = Json.MutableObject()) : JsonView(json) {
        val seats by keyed(::Seat)
    }

    @Test fun `a keyed list is an object by id iterated in insertion order updated in place`() {
        val table = Table()
        table.seats.add(Seat(Json.MutableObject("id" to "a", "name" to "Alice")))
        table.seats.add(Seat(Json.MutableObject("id" to "b", "name" to "Bob")))
        table.seats.add(Seat(Json.MutableObject("id" to "a", "name" to "Alicia")))   // a present id keeps its rank
        assertEquals("""{"seats":{"a":{"id":"a","name":"Alicia"},"b":{"id":"b","name":"Bob"}}}""", table.json.toString())
        assertEquals(listOf("Alicia", "Bob"), table.seats.map { it.name })
        assertEquals(2, table.seats.size)
        assertEquals("Bob", table.seats["b"]?.name)
        assertTrue("a" in table.seats)
        table.seats.remove("a")
        assertFalse("a" in table.seats)
        assertEquals("Bob", table.seats[0].name)
        assertFailsWith<IllegalArgumentException> { table.seats.add(Seat(Json.MutableObject("name" to "nobody"))) }
    }
}
