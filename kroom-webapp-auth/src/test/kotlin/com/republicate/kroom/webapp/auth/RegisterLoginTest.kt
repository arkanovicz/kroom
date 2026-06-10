package com.republicate.kroom.webapp.auth

import com.republicate.kroom.webapp.session.installSessions
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegisterLoginTest {

    private fun ApplicationTestBuilder.setup() {
        application {
            installSessions { sessionSecret = "test-secret" }
            installAuth<Int> { authStore = InMemoryAuthStore() }
        }
    }

    @Test
    fun `register then login (email match is case-insensitive)`() = testApplication {
        setup()
        val reg = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"Alice@example.com","password":"hunter2hunter","displayName":"Alice"}""")
        }
        assertEquals(HttpStatusCode.OK, reg.status)
        assertTrue(reg.bodyAsText().contains("\"authenticated\":true"))
        assertTrue(reg.bodyAsText().contains("\"id\":\"1\""))

        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alice@EXAMPLE.com","password":"hunter2hunter"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        assertTrue(login.bodyAsText().contains("\"authenticated\":true"))
    }

    @Test
    fun `duplicate email is rejected`() = testApplication {
        setup()
        val body = """{"email":"a@b.com","password":"longenough1","displayName":"A"}"""
        client.post("/api/auth/register") { contentType(ContentType.Application.Json); setBody(body) }
        val dup = client.post("/api/auth/register") { contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.Conflict, dup.status)
    }

    @Test
    fun `wrong password fails login`() = testApplication {
        setup()
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"c@d.com","password":"correctpass1","displayName":"C"}""")
        }
        val bad = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"c@d.com","password":"nope"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, bad.status)
    }

    @Test
    fun `unknown email fails login (same response as wrong password)`() = testApplication {
        setup()
        val bad = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"ghost@nowhere.com","password":"whatever123"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, bad.status)
    }

    @Test
    fun `short password rejected at register`() = testApplication {
        setup()
        val r = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"e@f.com","password":"short","displayName":"E"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }
}
