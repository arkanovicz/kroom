package com.republicate.kroom.webapp.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Ephemeral email-code machinery: pending registrations, guest upgrades and
 * password resets all hold a short-lived code keyed by normalized email.
 */

/** A pending code entry, keyed by normalized email. */
data class PendingCode(
    val code: String,
    /** Epoch ms after which the code is dead. */
    val expires: Long,
    /** Epoch ms of the last mail, for the resend cooldown. */
    val lastSent: Long,
    /** Failed verification attempts so far. */
    val attempts: Int = 0,
    /** Pending-registration payload (null for password reset). */
    val displayName: String? = null,
    val passwordHash: String? = null,
    /** Principal id (stringified) when the entry is a guest upgrade. */
    val upgradeId: String? = null
)

/**
 * Pluggable [PendingCode] storage. The in-memory default is fine for codes
 * (ephemeral — a lost code is just re-requested); plug a persistent
 * implementation for multi-instance deployments without sticky sessions.
 */
interface AuthCodeStore {
    suspend fun put(email: String, entry: PendingCode)
    suspend fun get(email: String): PendingCode?
    suspend fun remove(email: String)
}

class InMemoryAuthCodeStore : AuthCodeStore {
    private val entries = ConcurrentHashMap<String, PendingCode>()

    override suspend fun put(email: String, entry: PendingCode) {
        val now = System.currentTimeMillis()
        entries.entries.removeIf { it.value.expires < now }
        entries[email] = entry
    }

    override suspend fun get(email: String): PendingCode? = entries[email]

    override suspend fun remove(email: String) {
        entries.remove(email)
    }
}

private val secureRandom = SecureRandom()

/** Uniform random digits (leading zeros allowed). */
internal fun generateCode(length: Int): String =
    buildString { repeat(length) { append(secureRandom.nextInt(10)) } }

internal fun codeMatches(expected: String, given: String): Boolean =
    MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())

/** In-memory sliding-window rate limiter (per IP, per email). */
internal class RateLimiter(private val max: Int, private val windowMs: Long) {
    private val hits = ConcurrentHashMap<String, MutableList<Long>>()

    fun allow(key: String): Boolean {
        if (max <= 0) return true
        val now = System.currentTimeMillis()
        if (hits.size > 10_000) hits.entries.removeIf { entry -> entry.value.all { it < now - windowMs } }
        var allowed = false
        hits.compute(key) { _, present ->
            val list = present ?: mutableListOf()
            list.removeIf { it < now - windowMs }
            if (list.size < max) {
                list.add(now)
                allowed = true
            }
            list.takeIf { it.isNotEmpty() }
        }
        return allowed
    }
}
