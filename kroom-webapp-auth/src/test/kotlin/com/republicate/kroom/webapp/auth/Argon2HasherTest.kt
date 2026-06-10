package com.republicate.kroom.webapp.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class Argon2HasherTest {

    @Test
    fun `round trip verifies, wrong password fails`() {
        val hasher = Argon2Hasher()
        val encoded = hasher.hash("correct horse battery staple")
        assertTrue(encoded.startsWith("\$argon2id\$v=19\$"))
        assertTrue(hasher.verify("correct horse battery staple", encoded))
        assertFalse(hasher.verify("wrong", encoded))
    }

    @Test
    fun `salt is per-hash, so equal passwords hash differently`() {
        val hasher = Argon2Hasher()
        assertNotEquals(hasher.hash("same"), hasher.hash("same"))
    }

    @Test
    fun `pepper is required to verify a peppered hash`() {
        val peppered = Argon2Hasher(pepper = "server-pepper".toByteArray())
        val encoded = peppered.hash("pw")
        assertTrue(peppered.verify("pw", encoded))
        assertFalse(Argon2Hasher().verify("pw", encoded))
        assertFalse(Argon2Hasher(pepper = "other".toByteArray()).verify("pw", encoded))
    }

    @Test
    fun `verify reads cost from the hash, so old-cost hashes still verify`() {
        val cheap = Argon2Hasher(memoryKiB = 256, iterations = 1)
        val encoded = cheap.hash("x")
        // a hasher with stronger defaults still verifies the cheaper hash
        assertTrue(Argon2Hasher().verify("x", encoded))
    }

    @Test
    fun `malformed encoded strings are rejected, not thrown`() {
        val hasher = Argon2Hasher()
        assertFalse(hasher.verify("pw", ""))
        assertFalse(hasher.verify("pw", "not-a-phc-string"))
        assertFalse(hasher.verify("pw", "\$argon2id\$v=19\$m=bad,t=2,p=1\$c2FsdA\$aGFzaA"))
    }
}
