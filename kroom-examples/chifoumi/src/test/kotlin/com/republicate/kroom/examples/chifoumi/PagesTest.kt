package com.republicate.kroom.examples.chifoumi

import com.republicate.kroom.client.Bindings
import com.republicate.kroom.examples.chifoumi.game.LobbyLogic
import com.republicate.kroom.examples.chifoumi.game.LobbyState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The pages name only what the room declares: every `data-k-*` path a field (or `match`, derived), every `on` an event. */
class PagesTest {
    @Test fun `the lobby page binds to declared names`() {
        val html = File("src/main/resources/templates/index.html").readText()
        assertEquals(emptyList(), Bindings.check(html, LobbyLogic.registry, LobbyState(), derived = setOf("match")))
        assert(Bindings.scan(html).size >= 10) { "the page is bound" }
    }
}
