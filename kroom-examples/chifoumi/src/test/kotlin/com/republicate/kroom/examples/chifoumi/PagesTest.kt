package com.republicate.kroom.examples.chifoumi

import com.republicate.kroom.client.Bindings
import com.republicate.kroom.examples.chifoumi.game.LobbyLogic
import com.republicate.kroom.examples.chifoumi.game.LobbyState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The pages name only what the room declares: every `data-k-*` path a field (or a key the page derives: `match`, `game`), every `on` an event. */
class PagesTest {
    private fun check(page: String, vararg derived: String) {
        val html = File("src/main/resources/templates/$page").readText()
        assertEquals(emptyList(), Bindings.check(html, LobbyLogic.registry, LobbyState(), derived = derived.toSet()))
        assert(Bindings.scan(html).size >= 10) { "the page is bound" }
    }

    @Test fun `the lobby page binds to declared names`() = check("index.html", "match")

    @Test fun `the game page binds to declared names`() = check("game.html", "game")
}
