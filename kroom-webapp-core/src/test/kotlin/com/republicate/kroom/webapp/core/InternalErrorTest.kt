package com.republicate.kroom.webapp.core

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.HttpRequestLifecycle
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.Logger
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * What went wrong is the log's: a visitor learns only that something did. What ktor answers by itself — a request it
 * has a status for, a call its client left — is no error of ours.
 */
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

    @Test
    fun `a request ktor has a status for keeps it`() = testApplication {
        application {
            installCore()
            routing {
                get("/bad") { throw BadRequestException("no id") }
                get("/none") { throw NotFoundException() }
            }
        }
        assertEquals(HttpStatusCode.BadRequest, client.get("/bad").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/none").status)
    }

    /** Netty alone (the test host has no connection to lose): the cancellation is ktor's line, at debug, and nobody's error. */
    @Test
    fun `a call its client left is no error`() {
        val errors = CopyOnWriteArrayList<String>()
        val quiet = CompletableDeferred<Unit>()
        val log = Proxy.newProxyInstance(Logger::class.java.classLoader, arrayOf(Logger::class.java)) { _, method, args ->
            if (method.name == "error") errors += args?.firstOrNull().toString()
            if (method.name == "debug" && args?.any { it is CancellationException } == true) quiet.complete(Unit)
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> true
                String::class.java -> "test"
                else -> null
            }
        } as Logger
        val reached = CompletableDeferred<Unit>()
        val server = embeddedServer(Netty, applicationEnvironment { this.log = log }, { connector { port = 0 } }) {
            installCore()
            routing {
                route("/wait") {
                    install(HttpRequestLifecycle) { cancelCallOnClose = true }
                    get { reached.complete(Unit); awaitCancellation() }
                }
            }
        }.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            java.net.Socket("127.0.0.1", port).use { socket ->
                socket.getOutputStream().run {
                    write("GET /wait HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                    flush()
                }
                runBlocking { withTimeout(5000) { reached.await() } }
            }
            runBlocking { withTimeout(5000) { quiet.await() } }
            assertEquals(emptyList(), errors.toList())
        } finally {
            server.stop(100, 500)
        }
    }
}
