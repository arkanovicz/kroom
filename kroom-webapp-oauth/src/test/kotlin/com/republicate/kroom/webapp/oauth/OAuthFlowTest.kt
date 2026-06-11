package com.republicate.kroom.webapp.oauth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.oauth2.sdk.id.Issuer
import com.nimbusds.openid.connect.sdk.SubjectType
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata
import com.republicate.kroom.webapp.session.installSessions
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.URLDecoder
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Full authorization-code flow against a stubbed OIDC provider:
 * local token endpoint, canned JWT, injected JWKS — fully offline.
 */
class OAuthFlowTest {

    private lateinit var idp: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private var idpPort = 0
    private val rsaKey: RSAKey = RSAKeyGenerator(2048).keyID("test").generate()

    // nonce expected in the canned id_token, captured from the authorize redirect
    @Volatile
    private var idTokenNonce: String? = null

    @Volatile
    private var idTokenName: String? = "Test User"

    @BeforeTest
    fun startIdp() {
        idp = embeddedServer(Netty, port = 0) {
            routing {
                post("/token") {
                    call.respondText(
                        """{"access_token":"at","token_type":"Bearer","id_token":"${issueIdToken()}"}""",
                        ContentType.Application.Json
                    )
                }
            }
        }.start(wait = false)
        idpPort = runBlocking { idp.engine.resolvedConnectors().first().port }
    }

    @AfterTest
    fun stopIdp() {
        idp.stop(100, 1000)
    }

    private fun issuer() = "http://localhost:$idpPort"

    private fun issueIdToken(): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer())
            .subject("user-123")
            .audience("test-client")
            .expirationTime(Date(System.currentTimeMillis() + 60_000))
            .issueTime(Date())
            .claim("nonce", idTokenNonce)
            .claim("name", idTokenName)
            .claim("email", "test@example.com")
            .build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims).apply {
            sign(RSASSASigner(rsaKey))
        }.serialize()
    }

    private fun testMetadata(): OIDCProviderMetadata {
        val metadata = OIDCProviderMetadata(Issuer(issuer()), listOf(SubjectType.PUBLIC), URI("${issuer()}/jwks"))
        metadata.authorizationEndpointURI = URI("${issuer()}/authorize")
        metadata.tokenEndpointURI = URI("${issuer()}/token")
        return metadata
    }

    private fun testJwkSet() = JWKSet(rsaKey.toPublicJWK())

    private fun testProvider(requireNonce: Boolean = true): OidcProvider =
        OidcProvider(
            "test", "test-client", "test-secret",
            metadata = testMetadata(), jwkSet = testJwkSet(), requireNonce = requireNonce
        )

    private fun runFlow(
        configure: OAuthConfig.() -> Unit = {},
        provider: OidcProvider? = null,
        captureNonce: Boolean = true,
        checkAuthorize: (params: Map<String, String>) -> Unit = {},
        callback: suspend (client: HttpClient, state: String) -> HttpResponse = { client, state ->
            client.get("/oauth/callback?code=test-code&state=$state")
        },
        assertions: suspend (callback: HttpResponse, client: HttpClient) -> Unit
    ) = testApplication {
        val testProvider = provider ?: testProvider()
        application {
            installSessions { sessionSecret = "test-secret" }
            installOAuth {
                providers.add(testProvider)
                configure()
            }
        }
        val client = createClient {
            followRedirects = false
            install(HttpCookies)
        }

        val login = client.get("/oauth/login/test?returnTo=/after")
        assertEquals(HttpStatusCode.Found, login.status)
        val location = login.headers[HttpHeaders.Location]!!
        assertTrue(location.startsWith("${issuer()}/authorize"), location)
        val params = URI(location).rawQuery.split("&").associate {
            it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8")
        }
        if (captureNonce) idTokenNonce = params["nonce"]
        checkAuthorize(params)

        val response = callback(client, params["state"]!!)
        assertions(response, client)
    }

    @Test
    fun `full flow sets session and redirects to returnTo`() = runFlow(
        assertions = { callback, client ->
            assertEquals(HttpStatusCode.Found, callback.status)
            assertEquals("/after", callback.headers[HttpHeaders.Location])
            val user = client.get("/api/auth/user").bodyAsText()
            assertTrue(user.contains("user-123"), user)
            assertTrue(user.contains("test@example.com"), user)
        }
    )

    @Test
    fun `state mismatch rejects login`() = runFlow(
        callback = { client, _ -> client.get("/oauth/callback?code=test-code&state=forged") },
        assertions = { callback, client ->
            assertEquals(HttpStatusCode.Found, callback.status)
            assertEquals("/", callback.headers[HttpHeaders.Location])
            assertTrue(client.get("/api/auth/user").bodyAsText().contains("\"authenticated\":false"))
        }
    )

    @Test
    fun `provider error rejects login`() = runFlow(
        callback = { client, state -> client.get("/oauth/callback?error=access_denied&state=$state") },
        assertions = { callback, _ ->
            assertEquals("/", callback.headers[HttpHeaders.Location])
        }
    )

    @Test
    fun `missing nonce in id_token rejected by strict provider`() = runFlow(
        captureNonce = false,
        assertions = { callback, client ->
            assertEquals("/", callback.headers[HttpHeaders.Location])
            assertTrue(client.get("/api/auth/user").bodyAsText().contains("\"authenticated\":false"))
        }
    )

    @Test
    fun `nonce-less provider authenticates (LinkedIn style)`() = runFlow(
        provider = testProvider(requireNonce = false),
        captureNonce = false,
        assertions = { callback, client ->
            assertEquals("/after", callback.headers[HttpHeaders.Location])
            assertTrue(client.get("/api/auth/user").bodyAsText().contains("user-123"))
        }
    )

    @Test
    fun `apple-shaped form_post flow with first-auth name`() {
        idTokenName = null
        runFlow(
            provider = OidcProvider(
                "test", "test-client", null,
                metadata = testMetadata(), jwkSet = testJwkSet(),
                scope = listOf("openid", "email", "name"),
                extraAuthParams = mapOf("response_mode" to "form_post"),
                clientSecretSupplier = { "generated-jwt-secret" },
                clientSecretPost = true
            ),
            checkAuthorize = { params ->
                assertEquals("form_post", params["response_mode"])
                assertEquals("openid email name", params["scope"])
            },
            callback = { client, state ->
                client.submitForm("/oauth/callback", parameters {
                    append("code", "test-code")
                    append("state", state)
                    append("user", """{"name":{"firstName":"Jane","lastName":"Doe"},"email":"test@example.com"}""")
                })
            },
            assertions = { callback, client ->
                assertEquals("/after", callback.headers[HttpHeaders.Location])
                val user = client.get("/api/auth/user").bodyAsText()
                assertTrue(user.contains("Jane Doe"), user)
                assertTrue(user.contains("user-123"), user)
            }
        )
    }

    @Test
    fun `hook enriches session with appId`() = runFlow(
        configure = { onAuthenticated = { session, _ -> session.copy(appId = "dude-42") } },
        assertions = { _, client ->
            assertTrue(client.get("/api/auth/user").bodyAsText().contains("dude-42"))
        }
    )

    @Test
    fun `hook replaces session`() = runFlow(
        configure = { onAuthenticated = { session, _ -> session.copy(name = "Renamed") } },
        assertions = { _, client ->
            assertTrue(client.get("/api/auth/user").bodyAsText().contains("Renamed"))
        }
    )

    @Test
    fun `hook returning null rejects login`() = runFlow(
        configure = { onAuthenticated = { _, _ -> null } },
        assertions = { callback, client ->
            assertEquals("/", callback.headers[HttpHeaders.Location])
            assertTrue(client.get("/api/auth/user").bodyAsText().contains("\"authenticated\":false"))
        }
    )

    @Test
    fun `unknown provider returns 404`() = testApplication {
        application {
            installSessions { sessionSecret = "test-secret" }
            installOAuth {
                providers.add(testProvider())
            }
        }
        val client = createClient { followRedirects = false }
        assertEquals(HttpStatusCode.NotFound, client.get("/oauth/login/nope").status)
    }

    @Test
    fun `logout clears session`() = runFlow(
        assertions = { _, client ->
            client.get("/oauth/logout")
            assertTrue(client.get("/api/auth/user").bodyAsText().contains("\"authenticated\":false"))
        }
    )
}
