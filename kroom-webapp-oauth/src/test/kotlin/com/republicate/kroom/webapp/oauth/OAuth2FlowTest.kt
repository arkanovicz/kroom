package com.republicate.kroom.webapp.oauth

import com.republicate.kroom.webapp.session.installSessions
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.URLDecoder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Raw-OAuth2 path against a stubbed GitHub: code → access token → userinfo,
 * including the private-email fallback to /user/emails — fully offline.
 */
class OAuth2FlowTest {

    private lateinit var stub: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private var stubPort = 0

    @Volatile
    private var publicEmail: String? = null

    @Volatile
    private var tokenAcceptHeader: String? = null

    @BeforeTest
    fun startStub() {
        stub = embeddedServer(Netty, port = 0) {
            routing {
                post("/login/oauth/access_token") {
                    tokenAcceptHeader = call.request.headers[HttpHeaders.Accept]
                    call.respondText(
                        """{"access_token":"gh-token","token_type":"bearer","scope":"user:email"}""",
                        ContentType.Application.Json
                    )
                }
                get("/user") {
                    if (call.request.headers[HttpHeaders.Authorization] != "Bearer gh-token")
                        return@get call.respond(HttpStatusCode.Unauthorized)
                    val email = publicEmail?.let { "\"$it\"" } ?: "null"
                    call.respondText(
                        """{"id":12345,"login":"octocat","name":"The Octocat","email":$email}""",
                        ContentType.Application.Json
                    )
                }
                get("/user/emails") {
                    if (call.request.headers[HttpHeaders.Authorization] != "Bearer gh-token")
                        return@get call.respond(HttpStatusCode.Unauthorized)
                    call.respondText(
                        """[
                            {"email":"old@example.com","primary":false,"verified":true},
                            {"email":"primary@example.com","primary":true,"verified":true},
                            {"email":"unverified@example.com","primary":false,"verified":false}
                        ]""",
                        ContentType.Application.Json
                    )
                }
            }
        }.start(wait = false)
        stubPort = runBlocking { stub.engine.resolvedConnectors().first().port }
    }

    @AfterTest
    fun stopStub() {
        stub.stop(100, 1000)
    }

    private fun base() = "http://localhost:$stubPort"

    private fun runFlow(assertions: suspend (userJson: String) -> Unit) = testApplication {
        val githubBase = base()
        application {
            installSessions { sessionSecret = "test-secret" }
            installOAuth {
                providers.add(githubProvider("gh-client", "gh-secret", webBase = githubBase, apiBase = githubBase))
            }
        }
        val client = createClient {
            followRedirects = false
            install(io.ktor.client.plugins.cookies.HttpCookies)
        }

        val login = client.get("/oauth/login/github?returnTo=/after")
        assertEquals(HttpStatusCode.Found, login.status)
        val location = login.headers[HttpHeaders.Location]!!
        assertTrue(location.startsWith("$githubBase/login/oauth/authorize"), location)
        val params = URI(location).rawQuery.split("&").associate {
            it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8")
        }
        assertEquals("gh-client", params["client_id"])
        assertEquals("user:email", params["scope"])
        assertEquals("code", params["response_type"])

        val callback = client.get("/oauth/callback?code=gh-code&state=${params["state"]}")
        assertEquals(HttpStatusCode.Found, callback.status)
        assertEquals("/after", callback.headers[HttpHeaders.Location])
        assertEquals("application/json", tokenAcceptHeader)

        assertions(client.get("/api/auth/user").bodyAsText())
    }

    @Test
    fun `private email falls back to primary verified`() {
        publicEmail = null
        runFlow { user ->
            assertTrue(user.contains("\"id\":\"12345\""), user)
            assertTrue(user.contains("primary@example.com"), user)
            assertTrue(user.contains("The Octocat"), user)
            assertTrue(user.contains("\"provider\":\"github\""), user)
        }
    }

    @Test
    fun `public email is used directly`() {
        publicEmail = "visible@example.com"
        runFlow { user ->
            assertTrue(user.contains("visible@example.com"), user)
        }
    }
}
