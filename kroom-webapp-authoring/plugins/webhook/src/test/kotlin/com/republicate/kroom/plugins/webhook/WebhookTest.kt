package com.republicate.kroom.plugins.webhook

import com.republicate.kroom.webapp.authoring.IdentityProvider
import com.republicate.kroom.webapp.authoring.MemoryStorage
import com.republicate.kroom.webapp.authoring.Roles
import com.republicate.kroom.webapp.authoring.installContentSite
import com.republicate.kroom.webapp.session.UserSession
import com.sun.net.httpserver.HttpServer
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class WebhookTest {

    @Test
    fun `a publish is posted, signed, to the receiver`() = testApplication {
        val received = CompletableDeferred<Pair<String, String?>>()
        val receiver = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/hook") { exchange ->
                val body = exchange.requestBody.readAllBytes().decodeToString()
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
                received.complete(body to exchange.requestHeaders.getFirst("X-Kroom-Signature"))
            }
            start()
        }
        val storage = MemoryStorage().apply {
            settings("webhook")["url"] = "http://127.0.0.1:${receiver.address.port}/hook"
            settings("webhook")["secret"] = "s3cr3t"
        }
        application {
            installContentSite {
                this.storage = storage
                sessionSecret = "test-secret"
                identity = IdentityProvider { setOf(Roles.EDITOR) }
                plugins += Webhook()
            }
            routing { get("/as/{who}") { call.sessions.set(UserSession(call.parameters["who"]!!, "x", null, "t")); call.respondText("ok") } }
        }
        val author = createClient { install(HttpCookies) }.also { it.get("/as/alice") }
        author.post("/api/content/lock/pages/news.md")
        val submitted = author.post("/api/content/pages/news.md") {
            contentType(ContentType.Application.Json)
            setBody("""{"page":"/index","rev":"","body":"Hello"}""")
        }
        assertEquals(HttpStatusCode.OK, submitted.status)

        val (body, signature) = withTimeout(5000) { received.await() }
        assertContains(body, """"path":"pages/news.md"""")
        assertContains(body, """"author":"alice"""")
        assertEquals("sha256=" + Webhook.sign("s3cr3t", body), signature)
        receiver.stop(1)
    }
}
