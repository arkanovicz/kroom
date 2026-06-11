package com.republicate.kroom.webapp.oauth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date

/**
 * Sign in with Apple `client_secret`: an ES256 JWT signed with the `.p8` key
 * from the Apple Developer Portal, regenerated lazily (Apple caps validity at
 * 6 months; we refresh well before).
 */
class AppleClientSecret(
    private val teamId: String,
    private val keyId: String,
    private val clientId: String,
    privateKeyPem: String
) {
    private val privateKey: ECPrivateKey = parseKey(privateKeyPem)
    private var cached: String? = null
    private var refreshAt: Long = 0

    @Synchronized
    fun get(): String {
        val now = System.currentTimeMillis()
        cached?.takeIf { now < refreshAt }?.let { return it }
        val jwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId).build(),
            JWTClaimsSet.Builder()
                .issuer(teamId)
                .subject(clientId)
                .audience("https://appleid.apple.com")
                .issueTime(Date(now))
                .expirationTime(Date(now + VALIDITY_MS))
                .build()
        ).apply { sign(ECDSASigner(privateKey)) }.serialize()
        cached = jwt
        refreshAt = now + REFRESH_MS
        return jwt
    }

    companion object {
        private const val VALIDITY_MS = 180L * 24 * 3600 * 1000 // Apple max: 6 months
        private const val REFRESH_MS = 120L * 24 * 3600 * 1000

        /** Accepts the full `.p8` PEM or its bare base64 body. */
        private fun parseKey(pem: String): ECPrivateKey {
            val base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")
            val spec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64))
            return KeyFactory.getInstance("EC").generatePrivate(spec) as ECPrivateKey
        }
    }
}
