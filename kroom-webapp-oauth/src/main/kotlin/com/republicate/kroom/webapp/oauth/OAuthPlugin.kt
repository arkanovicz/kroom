package com.republicate.kroom.webapp.oauth

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.util.*
import org.pac4j.core.client.Clients
import org.pac4j.core.config.Config
import org.pac4j.oidc.client.GoogleOidcClient
import org.pac4j.oidc.config.OidcConfiguration

/**
 * OAuth plugin for Ktor webapps using PAC4J.
 *
 * Provides:
 * - Session management
 * - OAuth2/OIDC authentication
 * - User profile storage
 */

data class UserSession(
    val id: String,
    val name: String,
    val email: String?,
    val provider: String
)

class OAuthConfig {
    var sessionSecret: String = "change-me-in-production"
    var callbackUrl: String = "/oauth/callback"

    // Google OIDC
    var googleClientId: String? = null
    var googleClientSecret: String? = null

    // Generic OIDC
    var oidcDiscoveryUri: String? = null
    var oidcClientId: String? = null
    var oidcClientSecret: String? = null
}

private val OAuthConfigKey = AttributeKey<OAuthConfig>("OAuthConfig")
private val Pac4jConfigKey = AttributeKey<Config>("Pac4jConfig")

val Application.oauthConfig: OAuthConfig
    get() = attributes[OAuthConfigKey]

/**
 * Install OAuth plugin.
 *
 * Configures session storage and PAC4J clients.
 */
fun Application.installOAuth(block: OAuthConfig.() -> Unit = {}) {
    val config = OAuthConfig().apply(block)
    attributes.put(OAuthConfigKey, config)

    // Configure sessions
    install(Sessions) {
        cookie<UserSession>("user_session") {
            cookie.path = "/"
            cookie.maxAgeInSeconds = 3600 * 24 * 7 // 1 week
            cookie.httpOnly = true
            // In production, set cookie.secure = true
        }
    }

    // Build PAC4J clients
    val clients = mutableListOf<org.pac4j.core.client.Client>()

    // Google OIDC
    if (config.googleClientId != null && config.googleClientSecret != null) {
        val googleConfig = OidcConfiguration().apply {
            clientId = config.googleClientId
            secret = config.googleClientSecret
            discoveryURI = "https://accounts.google.com/.well-known/openid-configuration"
        }
        clients.add(GoogleOidcClient(googleConfig))
    }

    // Generic OIDC
    if (config.oidcDiscoveryUri != null && config.oidcClientId != null) {
        val oidcConfig = OidcConfiguration().apply {
            clientId = config.oidcClientId
            secret = config.oidcClientSecret
            discoveryURI = config.oidcDiscoveryUri
        }
        val oidcClient = org.pac4j.oidc.client.OidcClient(oidcConfig).apply {
            name = "oidc"
        }
        clients.add(oidcClient)
    }

    if (clients.isNotEmpty()) {
        val pac4jConfig = Config(Clients(config.callbackUrl, clients))
        attributes.put(Pac4jConfigKey, pac4jConfig)
    }

    routing {
        oauthRoutes()
    }
}

/**
 * OAuth routes for login/logout/callback.
 */
fun Route.oauthRoutes() {
    route("/oauth") {
        get("/login/{provider}") {
            // TODO: Redirect to provider
            call.respondRedirect("/")
        }

        get("/callback") {
            // TODO: Handle OAuth callback
            call.respondRedirect("/")
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
                }.toString(),
                io.ktor.http.ContentType.Application.Json
            )
        } else {
            call.respondText(
                """{"authenticated":false}""",
                io.ktor.http.ContentType.Application.Json
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
