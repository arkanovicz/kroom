package com.republicate.kroom.webapp.session

import io.ktor.server.application.*
import io.ktor.server.sessions.*
import io.ktor.util.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.net.URI
import java.security.MessageDigest

/**
 * Session substrate shared by the authentication modules (oauth, auth).
 *
 * Owns the single Ktor [Sessions] install so every cookie type is declared in
 * one place: the durable [UserSession] and the transient [AuthFlow] handshake.
 */

private val logger = LoggerFactory.getLogger("kroom.session")

const val DEFAULT_SESSION_SECRET = "change-me-in-production"

/** The durable authenticated subject, carried in an encrypted cookie. */
@Serializable
data class UserSession(
    val id: String,
    val name: String,
    val email: String?,
    val provider: String,
    val appId: String? = null
)

/** Transient state for a redirect-based login handshake (e.g. OIDC). */
@Serializable
data class AuthFlow(
    val provider: String,
    val state: String,
    val nonce: String,
    val returnTo: String
)

class SessionConfig {
    var sessionSecret: String = DEFAULT_SESSION_SECRET

    /** Public base URL (e.g. `https://example.com`); null derives from the request (dev only). */
    var externalUrl: String? = null

    /** Cookie domain (e.g. `.example.com`) so one login covers all subdomains; null = host-only. */
    var cookieDomain: String? = null

    /** Defaults to true when [externalUrl] is https. */
    var cookieSecure: Boolean? = null

    var userSessionMaxAgeSeconds: Long = 3600L * 24 * 7 // 1 week
    var flowMaxAgeSeconds: Long = 600 // login hop only
}

private val SessionConfigKey = AttributeKey<SessionConfig>("KroomSessionConfig")

val Application.sessionConfig: SessionConfig
    get() = attributes[SessionConfigKey]

val Application.sessionConfigOrNull: SessionConfig?
    get() = attributes.getOrNull(SessionConfigKey)

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
fun validateReturnTo(returnTo: String?, requestHost: String, cookieDomain: String?): String {
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
 * Install the single Ktor [Sessions] plugin, declaring the [UserSession] and
 * [AuthFlow] cookies. Must be called before [com.republicate.kroom.webapp.oauth.installOAuth]
 * / installAuth.
 */
fun Application.installSessions(block: SessionConfig.() -> Unit = {}) {
    val config = SessionConfig().apply(block)
    attributes.put(SessionConfigKey, config)

    if (config.sessionSecret == DEFAULT_SESSION_SECRET) {
        logger.warn("Using default session secret — set sessionSecret in production")
    }

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
            cookie.maxAgeInSeconds = config.userSessionMaxAgeSeconds
            transform(transformer)
        }
        cookie<AuthFlow>("auth_flow") {
            cookie.commonSettings()
            // cross-site form_post callbacks (Apple) need this cookie on a cross-origin POST;
            // None requires Secure, so plain-http dev keeps Lax
            if (secure) cookie.extensions["SameSite"] = "None"
            cookie.maxAgeInSeconds = config.flowMaxAgeSeconds
            transform(transformer)
        }
    }
}

/** Current user session (null if not authenticated). */
val ApplicationCall.userSession: UserSession?
    get() = sessions.get<UserSession>()

/** Whether a user session is present. */
val ApplicationCall.isAuthenticated: Boolean
    get() = userSession != null
