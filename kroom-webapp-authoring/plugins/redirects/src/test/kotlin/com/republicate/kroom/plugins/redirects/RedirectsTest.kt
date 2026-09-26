package com.republicate.kroom.plugins.redirects

import com.republicate.kroom.webapp.authoring.MemoryStorage
import com.republicate.kroom.webapp.authoring.installContentSite
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class RedirectsTest {

    private fun take(rule: String, url: String, resolvers: Map<String, (Map<String, String>) -> String?> = emptyMap()): String? {
        val parsed = Redirects.parse(rule).rules.single()
        val parameters = parametersOf(url.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
            .associate { it.substringBefore('=') to listOf(it.substringAfter('=')) })
        return parsed.match(url.substringBefore('?'), parameters)?.let { parsed.target(it, resolvers) }
    }

    @Test
    fun `rules match exactly, by prefix, or by captures in the path and the query`() {
        assertEquals("/new", take("/old /new", "/old"))
        assertEquals(null, take("/old /new", "/older"))
        assertEquals("/club/13Ma", take("/clubs/* /club/*", "/clubs/13Ma"))
        assertEquals("/tournament/42", take("/tournoi.php?id={id} /tournament/{id}", "/tournoi.php?id=42&lang=fr"))
        assertEquals(null, take("/tournoi.php?id={id} /tournament/{id}", "/tournoi.php"))
        assertEquals("/club/13Ma/news", take("/club-{code}.html /club/{code}/news", "/club-13Ma.html"))
        assertEquals("/p/a%2Fb", take("/x.php?id={id} /p/{id}", "/x.php?id=a/b"), "a value never adds a segment")
    }

    @Test
    fun `a resolver maps what only the application knows, and may pass`() {
        val players = mapOf<String, (Map<String, String>) -> String?>("player" to { v -> if (v["id"] == "1483") "/joueur/brisson-claude" else null })
        assertEquals("/joueur/brisson-claude", take("/affichePersonne.php?id={id} @player", "/affichePersonne.php?id=1483", players))
        assertEquals(null, take("/affichePersonne.php?id={id} @player", "/affichePersonne.php?id=9", players))
    }

    @Test
    fun `lines not understood are kept apart`() {
        assertEquals(listOf("/gone /elsewhere 418", "nope"), Redirects.parse("# comment\n/gone /elsewhere 418\nnope\n/a /b").errors)
    }

    @Test
    fun `a matching request is redirected before any route sees it, a miss is counted`() = testApplication {
        val storage = MemoryStorage().apply { settings("redirects")["rules"] = "/old /index\n/clubs/* /club/* 302" }
        application {
            installContentSite {
                this.storage = storage
                sessionSecret = "test-secret"
                plugins += Redirects()
            }
        }
        val browser = createClient { followRedirects = false }
        browser.get("/old").let {
            assertEquals(HttpStatusCode.MovedPermanently, it.status)
            assertEquals("/index", it.headers[HttpHeaders.Location])
        }
        browser.get("/clubs/13Ma").let {
            assertEquals(HttpStatusCode.Found, it.status)
            assertEquals("/club/13Ma", it.headers[HttpHeaders.Location])
        }
        assertEquals(HttpStatusCode.OK, browser.get("/index").status)
        repeat(2) { assertEquals(HttpStatusCode.NotFound, browser.get("/nowhere?x=1").status) }
        assertEquals(2L, storage.records("redirects", "misses").list().values.single { it.getString("uri") == "/nowhere?x=1" }.getLong("count"))
    }
}
