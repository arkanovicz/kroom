package com.republicate.kroom.webapp.core

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** A 404 nobody explained gets a body; a 404 that says why keeps saying it — an API's JSON error included. */
class NotFoundTest {

    @Test
    fun `a bare 404 gets its text, an explained one keeps its own`() = testApplication {
        application {
            installCore()
            routing {
                get("/api/thing") { respondError("no such thing", HttpStatusCode.NotFound, "noThing") }
            }
        }
        client.get("/nowhere").let {
            assertEquals(HttpStatusCode.NotFound, it.status)
            assertEquals("Not Found", it.bodyAsText())
        }
        client.get("/api/thing").let {
            assertEquals(HttpStatusCode.NotFound, it.status)
            assertEquals(ContentType.Application.Json, it.contentType()?.withoutParameters())
            assertContains(it.bodyAsText(), """"code":"noThing"""")
        }
    }
}
