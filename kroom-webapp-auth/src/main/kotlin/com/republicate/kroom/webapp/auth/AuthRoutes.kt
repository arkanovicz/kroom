package com.republicate.kroom.webapp.auth

import com.republicate.kroom.webapp.core.receiveJsonObject
import com.republicate.kroom.webapp.core.respondError
import com.republicate.kroom.webapp.core.respondJson
import com.republicate.kroom.webapp.session.UserSession
import com.republicate.kroom.webapp.session.userSession
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private const val MIN_PASSWORD_LENGTH = 8

internal val authLogger: Logger = LoggerFactory.getLogger("kroom.auth")

/**
 * Email+password routes: POST /api/auth/{register,login,logout,upgrade} always,
 * plus {verify,resend,forgot,reset} when a mailer is configured.
 */
fun <ID> Route.authRoutes(config: AuthConfig<ID>, hasher: Argon2Hasher, parser: (String) -> ID) {
    val store = config.authStore
    val mailer = config.mailer
    val verification = config.requireVerification && mailer != null
    val ipLimiter = RateLimiter(config.rateLimitPerMinute, 60_000L)
    val mailLimiter = RateLimiter(config.maxMailsPerDay, 24 * 3600_000L)

    // responds 429 and returns true when the caller is over the per-IP cap
    suspend fun RoutingContext.rateLimited(): Boolean {
        val ip = call.request.origin.remoteHost
        if (ipLimiter.allow(ip)) return false
        authLogger.warn("auth rate limit hit for {}", ip)
        respondError("too many requests", HttpStatusCode.TooManyRequests)
        return true
    }

    fun underCooldown(existing: PendingCode?): Boolean =
        existing != null && System.currentTimeMillis() - existing.lastSent < config.resendCooldownSeconds * 1000L

    // responds 502 and returns false on send failure (pending entry is kept for resend)
    suspend fun RoutingContext.sendCodeMail(email: String, code: String, template: (String) -> MailMessage): Boolean {
        val message = template(code)
        return try {
            mailer!!.send(email, message.subject, message.body)
            authLogger.info("code mail sent to {}", email)
            true
        } catch (e: Exception) {
            authLogger.error("code mail to {} failed: {}", email, e.message)
            respondError("could not send email", HttpStatusCode.BadGateway)
            false
        }
    }

    /** Constant-time, attempt-limited, TTL-checked code consumption; responds and returns null on failure. */
    suspend fun RoutingContext.consumeCode(codeStore: AuthCodeStore, email: String, code: String): PendingCode? {
        val entry = codeStore.get(email)
        val now = System.currentTimeMillis()
        if (entry == null || now > entry.expires) {
            if (entry != null) codeStore.remove(email)
            respondError("invalid or expired code", HttpStatusCode.Unauthorized)
            return null
        }
        if (entry.attempts >= config.maxVerifyAttempts) {
            codeStore.remove(email)
            authLogger.warn("verification attempts exhausted for {}", email)
            respondError("invalid or expired code", HttpStatusCode.Unauthorized)
            return null
        }
        if (!codeMatches(entry.code, code)) {
            codeStore.put(email, entry.copy(attempts = entry.attempts + 1))
            respondError("invalid or expired code", HttpStatusCode.Unauthorized)
            return null
        }
        codeStore.remove(email)
        return entry
    }

    route("/api/auth") {

        post("/register") {
            if (rateLimited()) return@post
            val body = receiveJsonObject()
            val emailRaw = body.getString("email")?.trim()
            val password = body.getString("password")
            val displayName = body.getString("displayName")?.trim()
            if (emailRaw.isNullOrBlank() || emailRaw.indexOf('@') <= 0)
                return@post respondError("invalid email")
            if (password == null || password.length < MIN_PASSWORD_LENGTH)
                return@post respondError("password must be at least $MIN_PASSWORD_LENGTH characters")
            if (displayName.isNullOrBlank())
                return@post respondError("displayName required")

            val email = normalizeEmail(emailRaw)
            if (store.findByNormalizedEmail(email) != null)
                return@post respondError("email already registered", HttpStatusCode.Conflict)

            if (verification) {
                if (underCooldown(config.verificationStore.get(email)) || !mailLimiter.allow(email))
                    return@post respondError("too many codes requested", HttpStatusCode.TooManyRequests)
                val code = generateCode(config.codeLength)
                val now = System.currentTimeMillis()
                config.verificationStore.put(
                    email,
                    PendingCode(
                        code = code,
                        expires = now + config.codeTtlSeconds * 1000L,
                        lastSent = now,
                        displayName = displayName,
                        passwordHash = hasher.hash(password)
                    )
                )
                if (!sendCodeMail(email, code, config.verifyEmail)) return@post
                return@post respondJson { set("pending", true) }
            }

            val principal = try {
                store.createPrincipal(email, displayName)
            } catch (e: AuthStoreException) {
                return@post respondError(e.message ?: "registration rejected", HttpStatusCode.Conflict)
            }
            store.createCredential(principal.id, "password", hasher.hash(password), null)
            call.setUserSession(principal, "password")
            respondAuthenticated(principal)
        }

        post("/login") {
            if (rateLimited()) return@post
            val body = receiveJsonObject()
            val emailRaw = body.getString("email")?.trim()
            val password = body.getString("password")
            if (emailRaw.isNullOrBlank() || password == null)
                return@post respondError("invalid credentials", HttpStatusCode.Unauthorized)

            val principal = store.findByNormalizedEmail(normalizeEmail(emailRaw))
            val hash = principal?.let { store.findCredential(it.id, "password") }?.passwordHash
            if (principal == null || hash == null || !hasher.verify(password, hash))
                return@post respondError("invalid credentials", HttpStatusCode.Unauthorized)

            store.touch(principal.id)
            call.setUserSession(principal, "password")
            respondAuthenticated(principal)
        }

        post("/logout") {
            call.sessions.clear<UserSession>()
            respondJson { set("authenticated", false) }
        }

        // guest upgrade: attach email+password to the authenticated no-email principal
        post("/upgrade") {
            if (rateLimited()) return@post
            val session = call.userSession
                ?: return@post respondError("not authenticated", HttpStatusCode.Unauthorized)
            if (session.email != null)
                return@post respondError("already registered", HttpStatusCode.Conflict)
            val body = receiveJsonObject()
            val emailRaw = body.getString("email")?.trim()
            val password = body.getString("password")
            if (emailRaw.isNullOrBlank() || emailRaw.indexOf('@') <= 0)
                return@post respondError("invalid email")
            if (password == null || password.length < MIN_PASSWORD_LENGTH)
                return@post respondError("password must be at least $MIN_PASSWORD_LENGTH characters")

            val email = normalizeEmail(emailRaw)
            if (store.findByNormalizedEmail(email) != null)
                return@post respondError("email already registered", HttpStatusCode.Conflict)

            if (verification) {
                if (underCooldown(config.verificationStore.get(email)) || !mailLimiter.allow(email))
                    return@post respondError("too many codes requested", HttpStatusCode.TooManyRequests)
                val code = generateCode(config.codeLength)
                val now = System.currentTimeMillis()
                config.verificationStore.put(
                    email,
                    PendingCode(
                        code = code,
                        expires = now + config.codeTtlSeconds * 1000L,
                        lastSent = now,
                        displayName = session.name,
                        passwordHash = hasher.hash(password),
                        upgradeId = session.id
                    )
                )
                if (!sendCodeMail(email, code, config.verifyEmail)) return@post
                return@post respondJson { set("pending", true) }
            }

            val id = parser(session.id)
            store.setEmail(id, email)
            store.setPassword(id, hasher.hash(password))
            store.touch(id)
            call.sessions.set(session.copy(email = email, provider = "password"))
            respondJson {
                set("authenticated", true)
                set("id", session.id)
                set("name", session.name)
            }
        }

        if (mailer != null) {

            post("/verify") {
                if (rateLimited()) return@post
                val body = receiveJsonObject()
                val emailRaw = body.getString("email")?.trim()
                val code = body.getString("code")?.trim()
                if (emailRaw.isNullOrBlank() || code.isNullOrBlank())
                    return@post respondError("email and code required")
                val email = normalizeEmail(emailRaw)
                val entry = consumeCode(config.verificationStore, email, code) ?: return@post

                if (store.findByNormalizedEmail(email) != null)
                    return@post respondError("email already registered", HttpStatusCode.Conflict)

                if (entry.upgradeId != null) {
                    val id = parser(entry.upgradeId)
                    store.setEmail(id, email)
                    store.setPassword(id, entry.passwordHash!!)
                    store.touch(id)
                    call.sessions.set(
                        UserSession(entry.upgradeId, entry.displayName ?: email, email, "password")
                    )
                    respondJson {
                        set("authenticated", true)
                        set("id", entry.upgradeId)
                        set("name", entry.displayName ?: email)
                    }
                } else {
                    val principal = try {
                        store.createPrincipal(email, entry.displayName!!)
                    } catch (e: AuthStoreException) {
                        return@post respondError(e.message ?: "registration rejected", HttpStatusCode.Conflict)
                    }
                    store.createCredential(principal.id, "password", entry.passwordHash, null)
                    call.setUserSession(principal, "password")
                    respondAuthenticated(principal)
                }
            }

            post("/resend") {
                if (rateLimited()) return@post
                val emailRaw = receiveJsonObject().getString("email")?.trim()
                if (emailRaw.isNullOrBlank())
                    return@post respondError("email required")
                val email = normalizeEmail(emailRaw)
                val entry = config.verificationStore.get(email)
                // always ok — don't leak whether a registration is pending
                if (entry == null || underCooldown(entry) || !mailLimiter.allow(email)) {
                    authLogger.info("resend for {} skipped (none pending, cooldown or daily cap)", email)
                    return@post respondJson { set("ok", true) }
                }
                val code = generateCode(config.codeLength)
                val now = System.currentTimeMillis()
                config.verificationStore.put(
                    email,
                    entry.copy(code = code, expires = now + config.codeTtlSeconds * 1000L, lastSent = now, attempts = 0)
                )
                if (!sendCodeMail(email, code, config.verifyEmail)) return@post
                respondJson { set("ok", true) }
            }

            post("/forgot") {
                if (rateLimited()) return@post
                val emailRaw = receiveJsonObject().getString("email")?.trim()
                if (emailRaw.isNullOrBlank())
                    return@post respondError("email required")
                val email = normalizeEmail(emailRaw)
                // always ok — don't leak which emails exist
                if (store.findByNormalizedEmail(email) == null
                    || underCooldown(config.resetStore.get(email))
                    || !mailLimiter.allow(email)
                ) {
                    authLogger.info("forgot for {} skipped (unknown, cooldown or daily cap)", email)
                    return@post respondJson { set("ok", true) }
                }
                val code = generateCode(config.codeLength)
                val now = System.currentTimeMillis()
                config.resetStore.put(
                    email,
                    PendingCode(code = code, expires = now + config.codeTtlSeconds * 1000L, lastSent = now)
                )
                if (!sendCodeMail(email, code, config.resetEmail)) return@post
                respondJson { set("ok", true) }
            }

            post("/reset") {
                if (rateLimited()) return@post
                val body = receiveJsonObject()
                val emailRaw = body.getString("email")?.trim()
                val code = body.getString("code")?.trim()
                val password = body.getString("password")
                if (emailRaw.isNullOrBlank() || code.isNullOrBlank())
                    return@post respondError("email and code required")
                if (password == null || password.length < MIN_PASSWORD_LENGTH)
                    return@post respondError("password must be at least $MIN_PASSWORD_LENGTH characters")
                val email = normalizeEmail(emailRaw)
                consumeCode(config.resetStore, email, code) ?: return@post
                val principal = store.findByNormalizedEmail(email)
                    ?: return@post respondError("invalid or expired code", HttpStatusCode.Unauthorized)
                store.setPassword(principal.id, hasher.hash(password))
                store.touch(principal.id)
                call.setUserSession(principal, "password")
                respondAuthenticated(principal)
            }
        }
    }
}

private fun <ID> ApplicationCall.setUserSession(principal: Principal<ID>, provider: String) {
    sessions.set(
        UserSession(
            id = principal.id.toString(),
            name = principal.displayName,
            email = principal.email,
            provider = provider
        )
    )
}

private suspend fun <ID> RoutingContext.respondAuthenticated(principal: Principal<ID>) {
    respondJson {
        set("authenticated", true)
        set("id", principal.id.toString())
        set("name", principal.displayName)
    }
}
