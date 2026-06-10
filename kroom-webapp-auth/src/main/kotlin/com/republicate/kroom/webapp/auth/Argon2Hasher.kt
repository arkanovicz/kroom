package com.republicate.kroom.webapp.auth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * argon2id password hashing (BouncyCastle, pure-JVM — no native lib).
 *
 * Hashes are self-describing PHC strings
 * (`$argon2id$v=19$m=<KiB>,t=<iter>,p=<par>$<salt>$<hash>`); [verify] reads the cost
 * parameters back out, so they can be raised over time without breaking existing
 * hashes. Salt is per-hash and random.
 *
 * Defaults are the OWASP argon2id baseline: 19 MiB memory, 2 iterations, 1 lane.
 *
 * @param pepper optional server-side secret folded in via argon2's `secret` parameter
 *   — kept out of the stored hash; the same value must be supplied to [verify].
 */
class Argon2Hasher(
    private val pepper: ByteArray? = null,
    private val memoryKiB: Int = 19_456,
    private val iterations: Int = 2,
    private val parallelism: Int = 1,
    private val hashLength: Int = 32,
    private val saltLength: Int = 16,
) {
    private val rng = SecureRandom()
    private val b64 = Base64.getEncoder().withoutPadding()
    private val b64d = Base64.getDecoder()

    fun hash(password: String): String {
        val salt = ByteArray(saltLength).also { rng.nextBytes(it) }
        val out = derive(password, salt, memoryKiB, iterations, parallelism, hashLength)
        return listOf(
            "", "argon2id", "v=19",
            "m=$memoryKiB,t=$iterations,p=$parallelism",
            b64.encodeToString(salt), b64.encodeToString(out)
        ).joinToString("$")
    }

    fun verify(password: String, encoded: String): Boolean {
        val parts = encoded.split('$')
        if (parts.size != 6 || parts[1] != "argon2id") return false
        val params = parts[3].split(',').mapNotNull {
            val kv = it.split('=')
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()
        val mem = params["m"]?.toIntOrNull() ?: return false
        val iter = params["t"]?.toIntOrNull() ?: return false
        val par = params["p"]?.toIntOrNull() ?: return false
        val salt = runCatching { b64d.decode(parts[4]) }.getOrNull() ?: return false
        val expected = runCatching { b64d.decode(parts[5]) }.getOrNull() ?: return false
        val actual = derive(password, salt, mem, iter, par, expected.size)
        return MessageDigest.isEqual(actual, expected) // constant-time
    }

    private fun derive(password: String, salt: ByteArray, mem: Int, iter: Int, par: Int, len: Int): ByteArray {
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(mem)
            .withIterations(iter)
            .withParallelism(par)
            .withSalt(salt)
        pepper?.let { builder.withSecret(it) }
        val generator = Argon2BytesGenerator().apply { init(builder.build()) }
        val out = ByteArray(len)
        generator.generateBytes(password.toByteArray(Charsets.UTF_8), out)
        return out
    }
}
