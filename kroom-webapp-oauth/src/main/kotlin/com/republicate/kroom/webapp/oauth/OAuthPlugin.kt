package com.republicate.kroom.webapp.oauth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.oauth2.sdk.AuthorizationCode
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant
import com.nimbusds.oauth2.sdk.ResponseType
import com.nimbusds.oauth2.sdk.Scope
import com.nimbusds.oauth2.sdk.TokenErrorResponse
import com.nimbusds.oauth2.sdk.TokenRequest
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic
import com.nimbusds.oauth2.sdk.auth.Secret
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.oauth2.sdk.id.State
import com.nimbusds.openid.connect.sdk.AuthenticationRequest
import com.nimbusds.openid.connect.sdk.Nonce
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.net.URI
import java.security.MessageDigest

/**
 * OAuth plugin for Ktor webapps — OIDC authorization-code flow.
 *
 * Provides:
 * - Encrypted cookie sessions
 * - OIDC authentication (Google + generic + custom providers)
 * - `onAuthenticated` hook for app-level enrichment or rejection
 */

private val logger = LoggerFactory.getLogger("kroom.oauth")

private const val DEFAULT_SECRET = "change-me-in-production"

@Serializable
data class UserSession(
    val id: String,
    val name: String,
    val email: String?,
    val provider: String,
    val appId: String? = null
)

/** Short-lived state carried across the login hop. */
@Serializable
data class OAuthFlow(
    val provider: String,
    val state: String,
    val nonce: String,
    val returnTo: String
)

class OAuthConfig {
    var sessionSecret: String = DEFAULT_SECRET
    var callbackUrl: String = "/oauth/callback"

    /** Public base URL (e.g. `https://republicate.com`); null derives from the request (dev only). */
    var externalUrl: String? = null

    /** Cookie domain (e.g. `.republicate.com`) so one login covers all subdomains; null = host-only. */
    var cookieDomain: String? = null

    /** Defaults to true when [externalUrl] is https. */
    var cookieSecure: Boolean? = null

    // Google OIDC
    var googleClientId: String? = null
    var googleClientSecret: String? = null

    // Generic OIDC
    var oidcDiscoveryUri: String? = null
    var oidcClientId: String? = null
    var oidcClientSecret: String? = null

    /** Additional providers beyond the google/oidc shortcuts. */
    val providers = mutableListOf<OidcProvider>()

    /**
     * Called after profile extraction, before the session is set.
     * Return a (possibly enriched) session to accept, null to reject the login.
     */
    var onAuthenticated: (suspend (session: UserSession, call: ApplicationCall) -> UserSession?)? = null
}

/**
 * An OIDC provider. Metadata is fetched from [discoveryUri] on first use,
 * or injected directly (tests, non-discoverable providers).
 */
class OidcProvider(
    val name: String,
    val clientId: String,
    val clientSecret: String?,
    private val discoveryUri: String? = null,
    metadata: OIDCProviderMetadata? = null,
    jwkSet: JWKSet? = null
) {
    init {
        require(discoveryUri != null || metadata != null) { "provider '$name' needs a discoveryUri or metadata" }
    }

    // benign race: concurrent first uses may fetch twice
    private var resolvedMetadata: OIDCProviderMetadata? = metadata
    private var resolvedValidator: IDTokenValidator? = null
    private val jwkSetOverride = jwkSet

    private suspend fun metadata(): OIDCProviderMetadata =
        resolvedMetadata ?: withContext(Dispatchers.IO) {
            OIDCProviderMetadata.parse(URI(discoveryUri!!).toURL().readText())
        }.also { resolvedMetadata = it }

    private suspend fun validator(): IDTokenValidator =
        resolvedValidator ?: metadata().let { md ->
            if (jwkSetOverride != null)
                IDTokenValidator(md.issuer, ClientID(clientId), JWSAlgorithm.RS256, jwkSetOverride)
            else
                IDTokenValidator(md.issuer, ClientID(clientId), JWSAlgorithm.RS256, md.jwkSetURI.toURL())
        }.also { resolvedValidator = it }

    internal suspend fun authRequestUri(redirectUri: String, state: State, nonce: Nonce): URI =
        AuthenticationRequest.Builder(
            ResponseType.CODE,
            Scope("openid", "profile", "email"),
            ClientID(clientId),
            URI(redirectUri)
        ).endpointURI(metadata().authorizationEndpointURI)
            .state(state)
            .nonce(nonce)
            .build()
            .toURI()

    internal suspend fun exchangeCode(code: String, redirectUri: String, nonce: String): IDTokenClaimsSet {
        val grant = AuthorizationCodeGrant(AuthorizationCode(code), URI(redirectUri))
        val request = if (clientSecret != null)
            TokenRequest(metadata().tokenEndpointURI, ClientSecretBasic(ClientID(clientId), Secret(clientSecret)), grant)
        else
            TokenRequest(metadata().tokenEndpointURI, ClientID(clientId), grant)
        val tokenEndpointUri = metadata().tokenEndpointURI
        return withContext(Dispatchers.IO) {
            val response = OIDCTokenResponseParser.parse(request.toHTTPRequest().send())
            if (!response.indicatesSuccess())
                throw OAuthFlowException("token exchange at $tokenEndpointUri failed: ${(response as TokenErrorResponse).errorObject}")
            val idToken = (response as OIDCTokenResponse).oidcTokens.idToken
                ?: throw OAuthFlowException("no id_token in token response")
            validator().validate(idToken, Nonce(nonce))
        }
    }
}

private class OAuthFlowException(message: String) : Exception(message)

private val OAuthConfigKey = AttributeKey<OAuthConfig>("OAuthConfig")
private val OidcProvidersKey = AttributeKey<Map<String, OidcProvider>>("OidcProviders")

val Application.oauthConfig: OAuthConfig
    get() = attributes[OAuthConfigKey]

// encryption key: AES-128, sign key: HmacSHA256
internal fun sessionTransformer(secret: String) = SessionTransportTransformerEncrypt(
    deriveKey(secret, "encrypt").copyOf(16),
    deriveKey(secret, "sign")
)

private fun deriveKey(secret: String, salt: String): ByteArray =
    MessageDigest.getInstance("SHA-256").digest("$salt:$secret".toByteArray())

/**
 * Validate a post-login redirect target: relative paths, the request host,
 * and hosts under [cookieDomain] are allowed; anything else falls back to `/`.
 */
internal fun validateReturnTo(returnTo: String?, requestHost: String, cookieDomain: String?): String {
    if (returnTo.isNullOrBlank()) return "/"
    if (returnTo.startsWith("/") && !returnTo.startsWith("//")) return returnTo
    val uri = try { URI(returnTo) } catch (e: Exception) { return "/" }
    if (uri.scheme != "http" && uri.scheme != "https") return "/"
    val host = uri.host ?: return "/"
    if (host == requestHost) return returnTo
    cookieDomain?.removePrefix(".")?.let { domain ->
        if (host == domain || host.endsWith(".$domain")) return returnTo
    }
    return "/"
}

/**
 * Install OAuth plugin.
 *
 * Configures encrypted cookie sessions, OIDC providers and routes.
 */
fun Application.installOAuth(block: OAuthConfig.() -> Unit = {}) {
    val config = OAuthConfig().apply(block)
    attributes.put(OAuthConfigKey, config)

    if (config.sessionSecret == DEFAULT_SECRET) {
        logger.warn("Using default session secret — set sessionSecret in production")
    }

    val providers = mutableMapOf<String, OidcProvider>()
    if (config.googleClientId != null && config.googleClientSecret != null) {
        providers["google"] = OidcProvider(
            "google", config.googleClientId!!, config.googleClientSecret!!,
            "https://accounts.google.com/.well-known/openid-configuration"
        )
    }
    if (config.oidcDiscoveryUri != null && config.oidcClientId != null) {
        providers["oidc"] = OidcProvider(
            "oidc", config.oidcClientId!!, config.oidcClientSecret, config.oidcDiscoveryUri
        )
    }
    config.providers.forEach { providers[it.name] = it }
    attributes.put(OidcProvidersKey, providers)

    val secure = config.cookieSecure ?: (config.externalUrl?.startsWith("https://") == true)
    val transformer = sessionTransformer(config.sessionSecret)
    fun CookieConfiguration.commonSettings() {
        path = "/"
        httpOnly = true
        this.secure = secure
        extensions["SameSite"] = "Lax"
        config.cookieDomain?.let { domain = it }
    }

    install(Sessions) {
        cookie<UserSession>("user_session") {
            cookie.commonSettings()
            cookie.maxAgeInSeconds = 3600 * 24 * 7 // 1 week
            transform(transformer)
        }
        cookie<OAuthFlow>("oauth_flow") {
            cookie.commonSettings()
            cookie.maxAgeInSeconds = 600 // login hop only
            transform(transformer)
        }
    }

    routing {
        oauthRoutes()
    }
}

/** Absolute redirect_uri: externalUrl when set, else derived from the request (dev). */
private fun ApplicationCall.callbackUri(config: OAuthConfig): String =
    config.externalUrl?.let { it.trimEnd('/') + config.callbackUrl }
        ?: with(request.origin) {
            val port = if (serverPort == 80 || serverPort == 443) "" else ":$serverPort"
            "$scheme://$serverHost$port${config.callbackUrl}"
        }

/**
 * OAuth routes for login/logout/callback.
 */
fun Route.oauthRoutes() {
    val config = application.oauthConfig
    val providers = application.attributes[OidcProvidersKey]

    route("/oauth") {
        get("/login/{provider}") {
            val provider = providers[call.parameters["provider"]]
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val returnTo = validateReturnTo(
                call.request.queryParameters["returnTo"] ?: call.request.headers[HttpHeaders.Referrer],
                call.request.origin.serverHost,
                config.cookieDomain
            )
            val state = State()
            val nonce = Nonce()
            call.sessions.set(OAuthFlow(provider.name, state.value, nonce.value, returnTo))
            call.respondRedirect(provider.authRequestUri(call.callbackUri(config), state, nonce).toString())
        }

        get("/callback") {
            val flow = call.sessions.get<OAuthFlow>()
            call.sessions.clear<OAuthFlow>()
            try {
                if (flow == null) throw OAuthFlowException("no pending oauth flow")
                val params = call.request.queryParameters
                params["error"]?.let { throw OAuthFlowException("provider error: $it") }
                if (params["state"] != flow.state) throw OAuthFlowException("state mismatch")
                val code = params["code"] ?: throw OAuthFlowException("missing code")
                val provider = providers[flow.provider]
                    ?: throw OAuthFlowException("unknown provider '${flow.provider}'")
                val claims = provider.exchangeCode(code, call.callbackUri(config), flow.nonce)
                var session = UserSession(
                    id = claims.subject.value,
                    name = claims.getStringClaim("name")
                        ?: claims.getStringClaim("preferred_username")
                        ?: claims.subject.value,
                    email = claims.getStringClaim("email"),
                    provider = provider.name
                )
                config.onAuthenticated?.let { hook ->
                    session = hook(session, call)
                        ?: throw OAuthFlowException("login rejected by onAuthenticated hook")
                }
                call.sessions.set(session)
                call.respondRedirect(flow.returnTo)
            } catch (e: Exception) {
                logger.warn("OAuth callback failed: {}", e.message)
                call.sessions.clear<UserSession>()
                call.respondRedirect("/")
            }
        }

        get("/logout") {
            call.sessions.clear<UserSession>()
            call.respondRedirect("/")
        }
    }

    get("/api/auth/user") {
        val session = call.sessions.get<UserSession>()
        if (session != null) {
            call.respondText(
                com.republicate.kson.Json.MutableObject().apply {
                    set("id", session.id)
                    set("name", session.name)
                    set("email", session.email)
                    set("provider", session.provider)
                    set("appId", session.appId)
                }.toString(),
                ContentType.Application.Json
            )
        } else {
            call.respondText(
                """{"authenticated":false}""",
                ContentType.Application.Json
            )
        }
    }
}

/**
 * Get current user session (null if not authenticated).
 */
val ApplicationCall.userSession: UserSession?
    get() = sessions.get<UserSession>()

/**
 * Check if user is authenticated.
 */
val ApplicationCall.isAuthenticated: Boolean
    get() = userSession != null
