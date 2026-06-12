package com.republicate.kroom.webapp.auth

import com.republicate.kroom.webapp.session.UserSession
import com.republicate.kroom.webapp.session.sessionConfigOrNull
import com.republicate.kroom.webapp.session.userSession
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Email+password authentication for Ktor webapps, with OIDC account linking.
 *
 * Requires `installSessions { }` (owns the session cookie). Adds register/login/logout
 * routes; the app provides an [AuthStore] for its own dude/credentials schema.
 */
class AuthConfig<ID> {
    /** App persistence for accounts and credentials. Required. */
    lateinit var authStore: AuthStore<ID>

    /** Optional server-side pepper folded into argon2id (kept out of the stored hash). */
    var pepper: String? = null

    /** Override the default argon2id parameters. */
    var hasher: Argon2Hasher? = null

    /** Override the `String → ID` parser (defaults cover Int/Long/String/Uuid). */
    var idFromString: ((String) -> ID)? = null

    /** App-supplied mail transport; required for verification, reset and coded upgrade. */
    var mailer: Mailer? = null

    /** Hold registrations behind an emailed code (effective only with a [mailer]). */
    var requireVerification: Boolean = true

    var codeTtlSeconds: Int = 600
    var codeLength: Int = 6
    var maxVerifyAttempts: Int = 5

    /** Minimum delay between code mails to the same address. */
    var resendCooldownSeconds: Int = 60

    /** Daily cap of code mails per address; 0 disables. */
    var maxMailsPerDay: Int = 5

    /** Per-IP request cap per minute on the auth routes; 0 disables. */
    var rateLimitPerMinute: Int = 10

    /** Pluggable code storage; defaults are in-memory. */
    var verificationStore: AuthCodeStore = InMemoryAuthCodeStore()
    var resetStore: AuthCodeStore = InMemoryAuthCodeStore()

    /** Override the verification mail (subject/body read config at call time). */
    var verifyEmail: (code: String) -> MailMessage = { code ->
        MailMessage(
            "Your verification code",
            "Your verification code is: $code\n\nIt expires in ${codeTtlSeconds / 60} minutes."
        )
    }

    /** Override the password-reset mail. */
    var resetEmail: (code: String) -> MailMessage = { code ->
        MailMessage(
            "Your password reset code",
            "Your password reset code is: $code\n\nIt expires in ${codeTtlSeconds / 60} minutes."
        )
    }
}

@PublishedApi
internal val IdParserKey = AttributeKey<(String) -> Any?>("KroomAuthIdParser")

/**
 * Install email+password auth. `installSessions { }` must be called first.
 */
inline fun <reified ID> Application.installAuth(block: AuthConfig<ID>.() -> Unit) {
    val config = AuthConfig<ID>().apply(block)
    val parser: (String) -> ID = config.idFromString ?: defaultIdParser()
    installAuthResolved(config, parser)
}

@PublishedApi
internal fun <ID> Application.installAuthResolved(config: AuthConfig<ID>, parser: (String) -> ID) {
    requireNotNull(sessionConfigOrNull) { "installSessions { } must be called before installAuth" }
    val hasher = config.hasher ?: Argon2Hasher(pepper = config.pepper?.toByteArray(Charsets.UTF_8))
    @Suppress("UNCHECKED_CAST")
    attributes.put(IdParserKey, parser as (String) -> Any?)
    if (config.requireVerification && config.mailer == null)
        authLogger.warn("requireVerification is set but no mailer is configured — registration proceeds unverified")
    routing { authRoutes(config, hasher, parser) }
}

/** Default `String → ID` parser for the built-in id types. */
@PublishedApi
@OptIn(ExperimentalUuidApi::class)
@Suppress("UNCHECKED_CAST")
internal inline fun <reified ID> defaultIdParser(): (String) -> ID = when (ID::class) {
    Int::class -> { s: String -> s.toInt() as ID }
    Long::class -> { s: String -> s.toLong() as ID }
    String::class -> { s: String -> s as ID }
    Uuid::class -> { s: String -> Uuid.parse(s) as ID }
    else -> error("No default id parser for ${ID::class.simpleName}; set idFromString in installAuth { }")
}

/** Current authenticated principal id, parsed from the session (null if absent/unparseable). */
@Suppress("UNCHECKED_CAST")
inline fun <reified ID> ApplicationCall.authId(): ID? {
    val sid = userSession?.id ?: return null
    val parser = application.attributes.getOrNull(IdParserKey)
        ?: return runCatching { defaultIdParser<ID>()(sid) }.getOrNull()
    return runCatching { parser(sid) as ID }.getOrNull()
}

/**
 * Link an OIDC-authenticated [session] to a stored principal by normalized email,
 * creating the principal and its provider credential on first sight. Returns the
 * session re-anchored to the local principal id (or null to reject — e.g. no email,
 * or the app's `createPrincipal` refused via [AuthStoreException]).
 *
 * Wire into `installOAuth { onAuthenticated = { s, _ -> authStore.linkOidc(s) } }`.
 */
suspend fun <ID> AuthStore<ID>.linkOidc(session: UserSession): UserSession? {
    val email = session.email ?: return null
    val normalized = normalizeEmail(email)
    val principal = findByNormalizedEmail(normalized)
        ?: try {
            createPrincipal(normalized, session.name)
        } catch (e: AuthStoreException) {
            return null
        }
    if (findCredential(principal.id, session.provider) == null) {
        createCredential(principal.id, session.provider, null, session.id)
    }
    touch(principal.id)
    return session.copy(
        id = principal.id.toString(),
        name = principal.displayName,
        email = principal.email
    )
}
