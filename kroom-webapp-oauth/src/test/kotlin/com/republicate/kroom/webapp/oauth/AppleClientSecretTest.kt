package com.republicate.kroom.webapp.oauth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.SignedJWT
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppleClientSecretTest {

    private val ecKey: ECKey = ECKeyGenerator(Curve.P_256).keyID("KEY1234567").generate()

    private fun p8Pem(): String {
        val pkcs8 = Base64.getEncoder().encodeToString(ecKey.toECPrivateKey().encoded)
        return "-----BEGIN PRIVATE KEY-----\n${pkcs8.chunked(64).joinToString("\n")}\n-----END PRIVATE KEY-----\n"
    }

    private fun secret() = AppleClientSecret("TEAM123456", "KEY1234567", "com.example.service", p8Pem())

    @Test
    fun `generates an ES256 JWT with Apple's expected structure`() {
        val jwt = SignedJWT.parse(secret().get())

        assertEquals(JWSAlgorithm.ES256, jwt.header.algorithm)
        assertEquals("KEY1234567", jwt.header.keyID)

        val claims = jwt.jwtClaimsSet
        assertEquals("TEAM123456", claims.issuer)
        assertEquals("com.example.service", claims.subject)
        assertEquals(listOf("https://appleid.apple.com"), claims.audience)
        val lifetimeMs = claims.expirationTime.time - claims.issueTime.time
        assertTrue(lifetimeMs <= 183L * 24 * 3600 * 1000, "exp must be ≤ 6 months")
    }

    @Test
    fun `signature verifies against the public key`() {
        val jwt = SignedJWT.parse(secret().get())
        assertTrue(jwt.verify(ECDSAVerifier(ecKey.toECPublicKey())))
    }

    @Test
    fun `secret is cached between calls`() {
        val s = secret()
        assertSame(s.get(), s.get())
    }

    @Test
    fun `accepts bare base64 key without PEM headers`() {
        val bare = Base64.getEncoder().encodeToString(ecKey.toECPrivateKey().encoded)
        val jwt = SignedJWT.parse(AppleClientSecret("TEAM123456", "KEY1234567", "com.example.service", bare).get())
        assertTrue(jwt.verify(ECDSAVerifier(ecKey.toECPublicKey())))
    }
}
