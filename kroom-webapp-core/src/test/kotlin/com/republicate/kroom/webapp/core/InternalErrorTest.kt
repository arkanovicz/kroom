package com.republicate.kroom.webapp.core

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** What went wrong is the log's: a visitor learns only that something did. */
class InternalErrorTest {

    @Test
    fun `an unhandled exception answers 500 without its message`() = testApplication {
        application {
            installCore()
            routing { get("/boom") { throw IllegalStateException("/srv/secret/templates/page.html: \$club.password") } }
        }
        client.get("/boom").let {
            assertEquals(HttpStatusCode.InternalServerError, it.status)
            val body = it.bodyAsText()
            assertFalse(body.contains("secret") || body.contains("password"), body)
            assertEquals("Internal Server Error", body)
        }
    }
}
