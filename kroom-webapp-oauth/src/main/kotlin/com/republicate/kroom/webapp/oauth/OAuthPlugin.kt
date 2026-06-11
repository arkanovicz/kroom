package com.republicate.kroom.webapp.oauth

import com.nimbusds.oauth2.sdk.id.State
import com.nimbusds.openid.connect.sdk.Nonce
import com.republicate.kroom.webapp.session.AuthFlow
import com.republicate.kroom.webapp.session.UserSession
import com.republicate.kroom.webapp.session.sessionConfig
import com.republicate.kroom.webapp.session.sessionConfigOrNull
import com.republicate.kroom.webapp.session.validateReturnTo
import com.republicate.kson.Json
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.util.*
import org.slf4j.LoggerFactory

/**
 * OAuth plugin for Ktor webapps — OIDC and raw-OAuth2 authorization-code flows.
 *
 * Requires [com.republicate.kroom.webapp.session.installSessions] to have run.
 * Provides authentication (google/apple/github/linkedin/oidc shortcuts + custom
 * providers) and an `onAuthenticated` hook for app-level enrichment or rejection.
 */

private val logger = LoggerFactory.getLogger("kroom.oauth")

class OAuthConfig {
    var callbackUrl: String = "/oauth/callback"

    // Google OIDC
    var googleClientId: String? = null
    var googleClientSecret: String? = null

    // Generic OIDC
    var oidcDiscoveryUri: String? = null
    var oidcClientId: String? = null
    var oidcClientSecret: String? = null

    /** Additional providers beyond the shortcuts. */
    val providers = mutableListOf<OAuthProvider>()

    /**
     * Called after profile extraction, before the session is set.
     * The session carries `provider`, `email`, and the stable per-provider
     * user id in `id` — enough to link accounts app-side.
     * Return a (possibly enriched) session to accept, null to reject the login.
     */
    var onAuthenticated: (suspend (session: UserSession, call: ApplicationCall) -> UserSession?)? = null
}

class AppleConfig {
    lateinit var teamId: String
    lateinit var keyId: String
    lateinit var servicesClientId: String
    /** `.p8` PEM content (or its bare base64 body). */
    lateinit var privateKey: String
}

/** Sign in with Apple: OIDC with a generated ES256 JWT secret and `form_post` callback. */
fun OAuthConfig.apple(block: AppleConfig.() -> Unit) {
    val apple = AppleConfig().apply(block)
    providers.add(
        OidcProvider(
            name = "apple",
            clientId = apple.servicesClientId,
            clientSecret = null,
            discoveryUri = "https://appleid.apple.com/.well-known/openid-configuration",
            scope = listOf("openid", "email", "name"),
            // Apple requires form_post when name/email scopes are requested
            extraAuthParams = mapOf("response_mode" to "form_post"),
            clientSecretSupplier = AppleClientSecret(apple.teamId, apple.keyId, apple.servicesClientId, apple.privateKey)::get,
            clientSecretPost = true
        )
    )
}

class GithubConfig {
    lateinit var clientId: String
    lateinit var clientSecret: String
}

/** GitHub: raw OAuth2 with the private-email fallback. */
fun OAuthConfig.github(block: GithubConfig.() -> Unit) {
    val github = GithubConfig().apply(block)
    providers.add(githubProvider(github.clientId, github.clientSecret))
}

class LinkedinConfig {
    lateinit var clientId: String
    lateinit var clientSecret: String
}

/** LinkedIn: standard OIDC, except the nonce is never echoed in the id_token. */
fun OAuthConfig.linkedin(block: LinkedinConfig.() -> Unit) {
    val linkedin = LinkedinConfig().apply(block)
    providers.add(
        OidcProvider(
            name = "linkedin",
            clientId = linkedin.clientId,
            clientSecret = linkedin.clientSecret,
            discoveryUri = "https://www.linkedin.com/oauth/.well-known/openid-configuration",
            requireNonce = false
        )
    )
}

internal class OAuthFlowException(message: String) : Exception(message)

private val OAuthConfigKey = AttributeKey<OAuthConfig>("OAuthConfig")
private val OAuthProvidersKey = AttributeKey<Map<String, OAuthProvider>>("OAuthProviders")

val Application.oauthConfig: OAuthConfig
    get() = attributes[OAuthConfigKey]

/**
 * Install OAuth plugin. Configures providers and routes.
 *
 * `installSessions { }` must be called first (owns the session/flow cookies).
 */
fun Application.installOAuth(block: OAuthConfig.() -> Unit = {}) {
    requireNotNull(sessionConfigOrNull) { "installSessions { } must be called before installOAuth" }
    val config = OAuthConfig().apply(block)
    attributes.put(OAuthConfigKey, config)

    val providers = mutableMapOf<String, OAuthProvider>()
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
    attributes.put(OAuthProvidersKey, providers)

    routing {
        oauthRoutes()
    }
}

/** Absolute redirect_uri: externalUrl when set, else derived from the request (dev). */
private fun ApplicationCall.callbackUri(config: OAuthConfig): String =
    application.sessionConfig.externalUrl?.let { it.trimEnd('/') + config.callbackUrl }
        ?: with(request.origin) {
            val port = if (serverPort == 80 || serverPort == 443) "" else ":$serverPort"
            "$scheme://$serverHost$port${config.callbackUrl}"
        }

/**
 * OAuth routes for login/logout/callback.
 */
fun Route.oauthRoutes() {
    val config = application.oauthConfig
    val providers = application.attributes[OAuthProvidersKey]

    route("/oauth") {
        get("/login/{provider}") {
            val provider = providers[call.parameters["provider"]]
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val returnTo = validateReturnTo(
                call.request.queryParameters["returnTo"] ?: call.request.headers[HttpHeaders.Referrer],
                call.request.origin.serverHost,
                application.sessionConfig.cookieDomain
            )
            val state = State()
            val nonce = Nonce()
            call.sessions.set(AuthFlow(provider.name, state.value, nonce.value, returnTo))
            call.respondRedirect(provider.authRequestUri(call.callbackUri(config), state, nonce).toString())
        }

        get("/callback") {
            handleCallback(call, config, providers, call.request.queryParameters)
        }

        // form_post response mode (Apple)
        post("/callback") {
            handleCallback(call, config, providers, call.receiveParameters())
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
                Json.MutableObject().apply {
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

private suspend fun handleCallback(
    call: ApplicationCall,
    config: OAuthConfig,
    providers: Map<String, OAuthProvider>,
    params: Parameters
) {
    val flow = call.sessions.get<AuthFlow>()
    call.sessions.clear<AuthFlow>()
    try {
        if (flow == null) throw OAuthFlowException("no pending oauth flow")
        params["error"]?.let { throw OAuthFlowException("provider error: $it") }
        if (params["state"] != flow.state) throw OAuthFlowException("state mismatch")
        val code = params["code"] ?: throw OAuthFlowException("missing code")
        val provider = providers[flow.provider]
            ?: throw OAuthFlowException("unknown provider '${flow.provider}'")
        val profile = provider.authenticate(code, call.callbackUri(config), flow.nonce)
        var session = UserSession(
            id = profile.id,
            name = profile.name ?: params["user"]?.let(::appleFirstAuthName) ?: profile.id,
            email = profile.email,
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

/** Apple posts a `user` JSON field (with the name) on the first authorization only. */
private fun appleFirstAuthName(userParam: String): String? = try {
    (Json.parse(userParam) as? Json.Object)?.getObject("name")?.let { name ->
        listOfNotNull(name.getString("firstName"), name.getString("lastName"))
            .joinToString(" ").ifBlank { null }
    }
} catch (e: Exception) {
    null
}
