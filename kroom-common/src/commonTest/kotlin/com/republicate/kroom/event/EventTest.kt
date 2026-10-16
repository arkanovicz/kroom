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
    }

    @Test fun `the json is the truth, the class a view`() {
        val play = Play(Json.MutableObject("move" to "ROCK"))
        assertEquals(Move.ROCK, play.move)
        assertNull(play.note)
        play.note = "gg"
        assertEquals("gg", play.json["note"])
        assertEquals("""{"move":"ROCK","note":"gg"}""", play.json.toString())
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

    @Test fun `validation names the field, accept refuses with it`() {
        assertEquals("missing {\"field\":\"move\"}", Play().validate().toString())
        assertEquals("invalid {\"field\":\"move\",\"kind\":\"STRING\"}", Play(Json.MutableObject("move" to "WELL")).validate().toString())
        assertEquals("invalid {\"field\":\"round\",\"kind\":\"INT\"}", Match(Json.MutableObject("player1" to "a", "player2" to "b", "round" to "x")).validate().toString())
        assertNull(Match(Json.MutableObject("player1" to "a", "player2" to "b")).validate())
        assertEquals("undeclared {\"field\":\"evil\"}", Play(Json.MutableObject("move" to "ROCK", "evil" to 1)).validate().toString())
        val e = assertFailsWith<Event.Rejected> { Play().accept("alice") }
        assertEquals("missing", e.reason.key)
        assertFailsWith<IllegalStateException> { Play().move }
    }

    @Test fun `one life, in order, stamped in the metadata`() {
        val play = Play(Json.MutableObject("move" to "PAPER"))
        assertEquals(Stage.BORN, play.stage)
        play.accept("alice", "t1")
        assertEquals(Stage.ACCEPTED, play.stage)
        assertEquals("alice", play.meta.from)
        assertEquals("t1", play.meta.tab)
        play.apply()
        assertNotNull(play.meta.at)
        play.emit(42)
        assertEquals(42L, play.meta.seq)
        assertEquals(Stage.APPLIED, play.stage) // the wire says the verdict
        assertFailsWith<IllegalStateException> { play.emit(43) }
        play.persist()
        assertEquals(Stage.PERSISTED, play.stage)
        assertEquals("""{"move":"PAPER"}""", play.json.toString()) // the metadata is the object's, never the wire's
    }

    @Test fun `a step out of order is the object's error`() {
        val play = Play(Json.MutableObject("move" to "PAPER"))
        assertFailsWith<IllegalStateException> { play.emit(1) }
        play.accept("alice").apply()
        assertFailsWith<IllegalStateException> { play.accept("bob") }
        assertFailsWith<IllegalStateException> { play.reject(Reason("late")) }
    }

    @Test fun `a rejection carries its reason and goes no further`() {
        val play = Play(Json.MutableObject("move" to "PAPER")).accept("alice")
        play.reject(Reason("not_your_turn", Json.Object("turn" to "bob")))
        assertEquals(Stage.REJECTED, play.stage)
        assertFailsWith<IllegalStateException> { play.emit(7) }
        assertFalse(play.emitted)
        assertFailsWith<IllegalStateException> { play.persist() }
        assertEquals("not_your_turn", play.meta.reason)
        assertEquals("bob", play.meta.args!!.getString("turn"))
    }

    @Test fun `a room-born event applies from birth`() {
        val match = Match(Json.MutableObject("player1" to "a", "player2" to "b")).apply().emit(1)
        assertEquals(Stage.APPLIED, match.stage)
        assertFailsWith<IllegalStateException> { Match(Json.MutableObject("player1" to "a", "player2" to "b")).persist() }
        assertNull(match.meta.from)
    }

    @Test fun `a registry rebuilds an intent from a frame and describes the alphabet`() {
        val catalog = Registry(::Play, ::Match)
        assertEquals(setOf("play", "match"), catalog.names)
        val e = catalog.of("play", """{"move":"SCISSORS"}""")
        assertIs<Play>(e)
        assertEquals(Stage.BORN, e.stage)
        assertEquals(Move.SCISSORS, e.move)
        assertNull(catalog.of("dance"))
        assertEquals(2, catalog.describe().size)
    }

    @Test fun `a client's metadata is dropped at birth`() {
        val json = Json.MutableObject("_" to Json.Object("from" to "x", "stage" to "APPLIED"), "move" to "ROCK")
        val play = Play(json)
        assertEquals(Stage.BORN, play.stage)
        assertNull(play.meta.from)
        assertNull(json["_"])
        assertNull(play.validate())
    }
}
