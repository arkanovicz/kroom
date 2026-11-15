package com.republicate.kroom.client

import org.apache.velocity.engine.VelocityEngine
import org.apache.velocity.engine.resource.ResourceLoader
import org.apache.velocity.engine.resource.content
import kotlin.test.Test
import kotlin.test.assertEquals

class LanguageTest {
    private val held = setOf("en/lobby.html", "en/game.html", "fr/lobby.html")
    private val engine = VelocityEngine(null).apply { addResourceLoader("held", ResourceLoader { name -> content("").takeIf { name in held } }) }

    @Test fun `a region in the page's language, else its primary tag's, else English`() {
        assertEquals("fr/lobby.html", engine.localized("fr") { "$it/lobby.html" })
        assertEquals("fr/lobby.html", engine.localized("fr-BE") { "$it/lobby.html" })
        assertEquals("en/game.html", engine.localized("fr") { "$it/game.html" })   // per region: what French lacks
        assertEquals("en/lobby.html", engine.localized("tr") { "$it/lobby.html" })
        assertEquals("en/lobby.html", engine.localized("en-150") { "$it/lobby.html" })
    }

    @Test fun `a region nobody holds is named in the fallback's language`() {
        assertEquals("en/none.html", engine.localized("fr") { "$it/none.html" })
    }

    @Test fun `plain English writes its dates as Europe does`() {
        assertEquals("en-150", localeOf("en"))
        assertEquals("en-GB", localeOf("en-GB"))
        assertEquals("fr", localeOf("fr"))
    }
}
