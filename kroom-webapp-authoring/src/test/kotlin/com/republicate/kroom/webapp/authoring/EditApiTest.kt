package com.republicate.kroom.webapp.authoring

import com.republicate.kroom.webapp.session.UserSession
import com.republicate.kroom.webapp.session.installSessions
import io.ktor.client.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.testing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The edit API's promises, each pinned where breaking it loses someone's text: a block is held by one author
 * at a time, a lock nobody touches expires instead of blocking the site forever, a submit built on an older
 * body is answered with theirs rather than overwriting it, and a successful submit gives the block back.
 */
class EditApiTest {

    private val path = "club/description.md"

    private fun ApplicationTestBuilder.serve(
        store: ResourceStore = MemoryResourceStore(),
        timeout: Duration = 2.minutes,
        mayEdit: (UserSession?, String) -> Boolean = { session, _ -> session != null }
    ) = application {
        installSessions { sessionSecret = "test-secret" }
        installAuthoring {
            this.store = store
            lockTimeout = timeout
            canEdit = mayEdit
        }
        // the test's way in: a session cookie for whoever asks
        routing {
            get("/as/{who}") {
                val who = call.parameters["who"]!!
                call.sessions.set(UserSession(who, who, null, "test"))
                call.respondText("ok")
            }
        }
    }

    private suspend fun ApplicationTestBuilder.actor(name: String): HttpClient =
        createClient { install(HttpCookies) }.also { it.get("/as/$name") }

    private suspend fun HttpClient.lock(path: String) = post("/api/content/lock/$path")
    private suspend fun HttpClient.cancel(path: String) = delete("/api/content/lock/$path")
    private suspend fun HttpClient.submit(path: String, rev: String, body: String) =
        post("/api/content/$path") {
            contentType(ContentType.Application.Json)
            setBody("""{"rev":"$rev","body":"$body"}""")
        }

    @Test
    fun `a block is held by one author, and given back on cancel`() = testApplication {
        serve()
        val alice = actor("alice")
        val bob = actor("bob")

        assertEquals(HttpStatusCode.OK, alice.lock(path).status)
        assertEquals(HttpStatusCode.OK, alice.lock(path).status)          // re-entrant for its owner

        val refused = bob.lock(path)
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertContains(refused.bodyAsText(), "alice")

        alice.cancel(path)
        assertEquals(HttpStatusCode.OK, bob.lock(path).status)
    }

    /** A closed tab must not hold a block hostage: expiry is read at the next reach, not scheduled. */
    @Test
    fun `a lock untouched past its timeout falls to the next author`() = testApplication {
        serve(timeout = 50.milliseconds)
        val alice = actor("alice")
        val bob = actor("bob")

        assertEquals(HttpStatusCode.OK, alice.lock(path).status)
        withContext(Dispatchers.Default) { delay(120) }                   // real time: the lock ages

        assertEquals(HttpStatusCode.OK, bob.lock(path).status)
        assertEquals(HttpStatusCode.Conflict, alice.submit(path, "", "mine").status)   // alice lost it
    }

    @Test
    fun `an unwritten block reads empty, with the empty rev and no lock`() = testApplication {
        serve()
        val payload = actor("alice").get("/api/content/$path").bodyAsText()
        assertContains(payload, """"body":""""")
        assertContains(payload, """"rev":""""")
        assertContains(payload, """"lock":null""")
        assertContains(payload, """"editable":true""")
    }

    @Test
    fun `a submit writes the block, attributes it and releases the lock`() = testApplication {
        val store = MemoryResourceStore()
        serve(store)
        val alice = actor("alice")
        val bob = actor("bob")

        alice.lock(path)
        val written = alice.submit(path, "", "## Les Vagabonds")
        assertEquals(HttpStatusCode.OK, written.status)

        val block = store.read(path)!!
        assertEquals("## Les Vagabonds", block.body)
        assertEquals("alice", block.author)
        assertContains(written.bodyAsText(), block.rev)
        assertEquals(HttpStatusCode.OK, bob.lock(path).status)            // the lock went with the submit
    }

    /** The rev is the safety net under the lock: an edit landed meanwhile is shown, never clobbered. */
    @Test
    fun `a submit against a stale rev is refused with theirs`() = testApplication {
        val store = MemoryResourceStore()
        serve(store)
        val alice = actor("alice")

        store.write(path, "written elsewhere", mapOf("author" to "nestor"))
        alice.lock(path)
        val conflict = alice.submit(path, "whatever-alice-started-from", "mine")

        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertContains(conflict.bodyAsText(), """"theirs"""")
        assertContains(conflict.bodyAsText(), "written elsewhere")
        assertEquals("written elsewhere", store.read(path)!!.body)
    }

    @Test
    fun `a submit without the lock is refused`() = testApplication {
        val store = MemoryResourceStore()
        serve(store)
        assertEquals(HttpStatusCode.Conflict, actor("alice").submit(path, "", "mine").status)
        assertNull(store.read(path))
    }

    @Test
    fun `an anonymous caller is 401`() = testApplication {
        serve()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/content/$path").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/content/lock/$path").status)
    }

    @Test
    fun `an author the application refuses is 403`() = testApplication {
        serve(mayEdit = { _, _ -> false })
        val alice = actor("alice")
        assertEquals(HttpStatusCode.Forbidden, alice.get("/api/content/$path").status)
        assertEquals(HttpStatusCode.Forbidden, alice.lock(path).status)
    }

    @Test
    fun `a versioned store answers its history, its journal and an old revision`() = testApplication {
        val store = VersionedMemoryResourceStore()
        serve(store)
        val alice = actor("alice")

        alice.lock(path)
        alice.submit(path, "", "un")
        val first = store.read(path)!!.rev
        alice.lock(path)
        alice.submit(path, first, "deux")

        assertContains(alice.get("/api/content/history/$path").bodyAsText(), first)
        assertContains(alice.get("/api/content/journal").bodyAsText(), path)
        assertContains(alice.get("/api/content/$path?rev=$first").bodyAsText(), """"body":"un"""")
    }

    @Test
    fun `a store that keeps no past has no history, journal or old revision`() = testApplication {
        serve()
        val alice = actor("alice")
        assertEquals(HttpStatusCode.NotFound, alice.get("/api/content/history/$path").status)
        assertEquals(HttpStatusCode.NotFound, alice.get("/api/content/journal").status)
        assertEquals(HttpStatusCode.NotFound, alice.get("/api/content/$path?rev=deadbeef").status)
    }
}
