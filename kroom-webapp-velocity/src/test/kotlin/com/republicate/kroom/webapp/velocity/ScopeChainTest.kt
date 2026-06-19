package com.republicate.kroom.webapp.velocity

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ScopeChainTest {

    // templatePath = null so getTemplate resolves test resources at the classpath root.

    @Test
    fun `session-scope value is rendered without the route passing it`() = testApplication {
        application {
            installVelocity { templatePath = null; session("user") { "alice" } }
            routing { get("/") { call.respondVelocity("user.vm") } }
        }
        assertEquals("alice", client.get("/").bodyAsText())
    }

    @Test
    fun `route model overrides a base-scope value on collision`() = testApplication {
        application {
            installVelocity { templatePath = null; session("user") { "alice" } }
            routing { get("/") { call.respondVelocity("user.vm", mapOf("user" to "bob")) } }
        }
        assertEquals("bob", client.get("/").bodyAsText())
    }

    @Test
    fun `a null session value reads as logged-out (the footgun this fixes)`() = testApplication {
        application {
            installVelocity { templatePath = null; session("user") { null } }
            routing { get("/") { call.respondVelocity("auth.vm") } }
        }
        assertEquals("out", client.get("/").bodyAsText())
    }

    @Test
    fun `scopes resolve per call (request-scope value reads the call)`() = testApplication {
        application {
            installVelocity { templatePath = null; session("user") { it.request.queryParameters["u"] } }
            routing { get("/") { call.respondVelocity("auth.vm") } }
        }
        assertEquals("in:zoe", client.get("/?u=zoe").bodyAsText())
        assertEquals("out", client.get("/").bodyAsText())
    }
}
