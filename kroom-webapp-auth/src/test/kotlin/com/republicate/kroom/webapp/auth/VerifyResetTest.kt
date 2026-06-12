package com.republicate.kroom.webapp.auth

import com.republicate.kroom.webapp.session.UserSession
import com.republicate.kroom.webapp.session.installSessions
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Records instead of sending; codes are read back from the bodies. */
private class RecordingMailer : Mailer {
    val sent = mutableListOf<Triple<String, String, String>>()
    var fail = false

    override suspend fun send(to: String, subject: String, body: String) {
        if (fail) throw RuntimeException("smtp down")
        sent.add(Triple(to, subject, body))
    }

    fun lastCode(): String = Regex("\\b(\\d{6})\\b").find(sent.last().third)!!.groupValues[1]
}

class VerifyResetTest {

    private fun ApplicationTestBuilder.setup(
        mailer: Mailer?,
        configure: AuthConfig<Int>.() -> Unit = {}
    ): InMemoryAuthStore {
        val store = InMemoryAuthStore()
        application {
            installSessions { sessionSecret = "test-secret" }
            installAuth<Int> {
                authStore = store
                this.mailer = mailer
                configure()
            }
            // app-side guest creation (kroom only owns the upgrade)
            routing {
                post("/test/guest") {
                    val principal = store.createPrincipal(null, "Guesty")
                    call.sessions.set(UserSession(principal.id.toString(), principal.displayName, null, "guest"))
                    call.respondText("""{"id":"${principal.id}"}""", ContentType.Application.Json)
                }
            }
        }
        return store
    }

    private fun ApplicationTestBuilder.cookieClient(): HttpClient =
        createClient { install(HttpCookies) }

    private suspend fun HttpClient.postJson(url: String, body: String): HttpResponse =
        post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.register(email: String = "alice@example.com"): HttpResponse =
        postJson("/api/auth/register", """{"email":"$email","password":"hunter2hunter","displayName":"Alice"}""")

    // -- verification --

    @Test
    fun `register holds pending until verify`() = testApplication {
        val mailer = RecordingMailer()
        val store = setup(mailer)
        val client = cookieClient()

        val reg = client.register()
        assertEquals(HttpStatusCode.OK, reg.status)
        assertTrue(reg.bodyAsText().contains("\"pending\":true"))
        assertNull(runBlocking { store.findByNormalizedEmail("alice@example.com") })
        assertEquals(1, mailer.sent.size)
        assertEquals("alice@example.com", mailer.sent.last().first)

        val verify = client.postJson(
            "/api/auth/verify",
            """{"email":"alice@example.com","code":"${mailer.lastCode()}"}"""
        )
        assertEquals(HttpStatusCode.OK, verify.status)
        assertTrue(verify.bodyAsText().contains("\"authenticated\":true"))
        assertNotNull(runBlocking { store.findByNormalizedEmail("alice@example.com") })

        val login = client.postJson("/api/auth/login", """{"email":"alice@example.com","password":"hunter2hunter"}""")
        assertEquals(HttpStatusCode.OK, login.status)
    }

    @Test
    fun `wrong code attempts are limited`() = testApplication {
        val mailer = RecordingMailer()
        setup(mailer) { maxVerifyAttempts = 2 }
        val client = cookieClient()
        client.register()
        val good = mailer.lastCode()
        val bad = if (good == "000000") "111111" else "000000"

        repeat(2) {
            val r = client.postJson("/api/auth/verify", """{"email":"alice@example.com","code":"$bad"}""")
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }
        // attempts exhausted: even the right code is dead now
        val r = client.postJson("/api/auth/verify", """{"email":"alice@example.com","code":"$good"}""")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun `expired code is rejected`() = testApplication {
        val mailer = RecordingMailer()
        setup(mailer) { codeTtlSeconds = -1 }
        val client = cookieClient()
        client.register()
        val r = client.postJson("/api/auth/verify", """{"email":"alice@example.com","code":"${mailer.lastCode()}"}""")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun `resend issues a new working code`() = testApplication {
        val mailer = RecordingMailer()
        setup(mailer) { resendCooldownSeconds = 0 }
        val client = cookieClient()
        client.register()
        val first = mailer.lastCode()

        val resend = client.postJson("/api/auth/resend", """{"email":"alice@example.com"}""")
        assertEquals(HttpStatusCode.OK, resend.status)
        assertEquals(2, mailer.sent.size)
        val second = mailer.lastCode()
        assertNotEquals(first, second, "new code must replace the old one (random collision is 1e-6)")

        val verify = client.postJson("/api/auth/verify", """{"email":"alice@example.com","code":"$second"}""")
        assertEquals(HttpStatusCode.OK, verify.status)
    }

    @Test
    fun `resend under cooldown says ok but sends nothing`() = testApplication {
        val mailer = RecordingMailer()
        setup(mailer) { resendCooldownSeconds = 3600 }
        val client = cookieClient()
        client.register()
        val resend = client.postJson("/api/auth/resend", """{"email":"alice@example.com"}""")
        assertEquals(HttpStatusCode.OK, resend.status)
        assertTrue(resend.bodyAsText().contains("\"ok\":true"))
        assertEquals(1, mailer.sent.size)
    }

    @Test
    fun `mail failure responds 502 and resend recovers`() = testApplication {
        val mailer = RecordingMailer().apply { fail = true }
        setup(mailer) { resendCooldownSeconds = 0 }
        val client = cookieClient()
        assertEquals(HttpStatusCode.BadGateway, client.register().status)

        mailer.fail = false
        val resend = client.postJson("/api/auth/resend", """{"email":"alice@example.com"}""")
        assertEquals(HttpStatusCode.OK, resend.status)
        val verify = client.postJson("/api/auth/verify", """{"email":"alice@example.com","code":"${mailer.lastCode()}"}""")
        assertEquals(HttpStatusCode.OK, verify.status)
    }

    @Test
    fun `requireVerification=false registers immediately even with mailer`() = testApplication {
        val mailer = RecordingMailer()
        val store = setup(mailer) { requireVerification = false }
        val client = cookieClient()
        val reg = client.register()
        assertTrue(reg.bodyAsText().contains("\"authenticated\":true"))
        assertEquals(0, mailer.sent.size)
        assertNotNull(runBlocking { store.findByNormalizedEmail("alice@example.com") })
    }

    @Test
    fun `verify routes absent without mailer`() = testApplication {
        setup(mailer = null)
        val r = client.post("/api/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"a@b.com","code":"123456"}""")
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    // -- guest upgrade --

    @Test
    fun `guest upgrade attaches email and password to the same principal`() = testApplication {
        val mailer = RecordingMailer()
        val store = setup(mailer)
        val client = cookieClient()

        val guest = client.post("/test/guest").bodyAsText()
        assertTrue(guest.contains("\"id\":\"1\""), guest)

        val upgrade = client.postJson(
            "/api/auth/upgrade",
            """{"email":"guest@example.com","password":"hunter2hunter"}"""
        )
        assertEquals(HttpStatusCode.OK, upgrade.status)
        assertTrue(upgrade.bodyAsText().contains("\"pending\":true"))

        val verify = client.postJson(
            "/api/auth/verify",
            """{"email":"guest@example.com","code":"${mailer.lastCode()}"}"""
        )
        assertEquals(HttpStatusCode.OK, verify.status)
        assertTrue(verify.bodyAsText().contains("\"id\":\"1\""), "principal id must be preserved")

        val principal = runBlocking { store.findByNormalizedEmail("guest@example.com") }
        assertEquals(1, principal?.id)
        assertEquals("Guesty", principal?.displayName)

        val login = client.postJson("/api/auth/login", """{"email":"guest@example.com","password":"hunter2hunter"}""")
        assertEquals(HttpStatusCode.OK, login.status)
        assertTrue(login.bodyAsText().contains("\"id\":\"1\""))
    }

    @Test
    fun `upgrade requires an authenticated session`() = testApplication {
        setup(RecordingMailer())
        val r = client.post("/api/auth/upgrade") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"x@y.com","password":"hunter2hunter"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    // -- password reset --

    @Test
    fun `forgot then reset updates the password and logs in`() = testApplication {
        val mailer = RecordingMailer()
        setup(mailer) { requireVerification = false }
        val client = cookieClient()
        client.register()

        val forgot = client.postJson("/api/auth/forgot", """{"email":"alice@example.com"}""")
        assertEquals(HttpStatusCode.OK, forgot.status)
        assertTrue(forgot.bodyAsText().contains("\"ok\":true"))

        val reset = client.postJson(
            "/api/auth/reset",
            """{"email":"alice@example.com","code":"${mailer.lastCode()}","password":"betterpassword"}"""
        )
        assertEquals(HttpStatusCode.OK, reset.status)
        assertTrue(reset.bodyAsText().contains("\"authenticated\":true"), "reset auto-logs-in")

        val old = client.postJson("/api/auth/login", """{"email":"alice@example.com","password":"hunter2hunter"}""")
        assertEquals(HttpStatusCode.Unauthorized, old.status)
        val new = client.postJson("/api/auth/login", """{"email":"alice@example.com","password":"betterpassword"}""")
        assertEquals(HttpStatusCode.OK, new.status)
    }

    @Test
    fun `forgot does not leak which emails exist`() = testApplication {
        val mailer = RecordingMailer()
        setup(mailer) { requireVerification = false }
        val r = client.post("/api/auth/forgot") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"ghost@nowhere.com"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"ok\":true"))
        assertEquals(0, mailer.sent.size)
    }

    @Test
    fun `reset creates the password credential for an oidc-only account`() = testApplication {
        val mailer = RecordingMailer()
        val store = setup(mailer) { requireVerification = false }
        val client = cookieClient()
        runBlocking { store.createPrincipal("linked@example.com", "Linked") } // no password credential

        client.postJson("/api/auth/forgot", """{"email":"linked@example.com"}""")
        val reset = client.postJson(
            "/api/auth/reset",
            """{"email":"linked@example.com","code":"${mailer.lastCode()}","password":"freshpassword"}"""
        )
        assertEquals(HttpStatusCode.OK, reset.status)

        val login = client.postJson("/api/auth/login", """{"email":"linked@example.com","password":"freshpassword"}""")
        assertEquals(HttpStatusCode.OK, login.status)
    }

    // -- rate limiting --

    @Test
    fun `per-IP rate limit responds 429`() = testApplication {
        setup(RecordingMailer()) { rateLimitPerMinute = 2 }
        val client = cookieClient()
        client.register("a@example.com")
        client.register("b@example.com")
        val third = client.register("c@example.com")
        assertEquals(HttpStatusCode.TooManyRequests, third.status)
    }
}
