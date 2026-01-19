package com.republicate.kroom.webapp.push

import com.republicate.kson.Json
import nl.martijndwars.webpush.Notification
import nl.martijndwars.webpush.PushService as WebPushService
import nl.martijndwars.webpush.Subscription
import org.apache.http.util.EntityUtils
import org.slf4j.LoggerFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Web Push notification service using VAPID.
 *
 * Usage:
 * 1. Generate VAPID keys once: `PushService.generateVapidKeys()`
 * 2. Store them in config
 * 3. Init on startup: `PushService.init(publicKey, privateKey, subject)`
 * 4. Send notifications: `PushService.send(subscription, payload)`
 */
object PushService {
    private val logger = LoggerFactory.getLogger("kroom.push")

    // VAPID keys
    var vapidPublicKey: String? = null
        private set
    var vapidPrivateKey: String? = null
        private set
    var vapidSubject: String = "mailto:webmaster@example.com"
        private set

    private var pushService: WebPushService? = null

    /**
     * Initialize the push service with VAPID keys.
     *
     * @param publicKey Base64-encoded VAPID public key
     * @param privateKey Base64-encoded VAPID private key
     * @param subject VAPID subject (mailto: or https: URL)
     */
    fun init(publicKey: String, privateKey: String, subject: String) {
        // Add BouncyCastle provider if not present
        ensureBouncyCastle()

        vapidPublicKey = publicKey
        vapidPrivateKey = privateKey
        vapidSubject = subject

        pushService = WebPushService(publicKey, privateKey, subject)
        logger.info("Push service initialized with VAPID public key: ${publicKey.take(20)}...")
    }

    private fun ensureBouncyCastle() {
        try {
            val bcProvider = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
            if (Security.getProvider("BC") == null) {
                Security.addProvider(bcProvider.getDeclaredConstructor().newInstance() as java.security.Provider)
            }
        } catch (e: ClassNotFoundException) {
            // BouncyCastle not available, web-push should handle it
        }
    }

    /**
     * Check if push service is configured.
     */
    fun isConfigured(): Boolean = pushService != null

    /**
     * Send notification to a subscription.
     *
     * @param endpoint Push endpoint URL
     * @param p256dh User's public key
     * @param auth User's auth secret
     * @param payload JSON payload to send
     * @return SendResult indicating success or failure reason
     */
    fun send(endpoint: String, p256dh: String, auth: String, payload: Json.Object): SendResult {
        val service = pushService ?: return SendResult.NotConfigured

        return try {
            val sub = Subscription(endpoint, Subscription.Keys(p256dh, auth))
            val notification = Notification(sub, payload.toString())
            val response = service.send(notification)

            val statusCode = response.statusLine.statusCode
            when (statusCode) {
                201 -> {
                    logger.debug("Push sent successfully to ${endpoint.take(50)}...")
                    SendResult.Success
                }
                404, 410 -> {
                    logger.info("Subscription expired: ${endpoint.take(50)}...")
                    SendResult.Expired
                }
                else -> {
                    val body = response.entity?.let { EntityUtils.toString(it) } ?: ""
                    logger.warn("Push failed with status $statusCode: $body")
                    SendResult.Failed(statusCode, body)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to send push notification", e)
            SendResult.Error(e)
        }
    }

    /**
     * Create a standard notification payload.
     */
    fun createPayload(
        title: String,
        body: String,
        url: String = "/",
        tag: String = "notification",
        icon: String? = null
    ): Json.Object {
        val pairs = mutableListOf(
            "title" to title,
            "body" to body,
            "url" to url,
            "tag" to tag
        )
        if (icon != null) pairs.add("icon" to icon)
        return Json.Object(*pairs.toTypedArray())
    }

    /**
     * Generate a new VAPID key pair.
     * Call once to generate keys, then store them in configuration.
     *
     * @return Pair of (publicKey, privateKey) as Base64 URL-safe strings
     */
    fun generateVapidKeys(): Pair<String, String> {
        ensureBouncyCastle()

        val keyGen = KeyPairGenerator.getInstance("EC")
        keyGen.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val keyPair = keyGen.generateKeyPair()

        val publicKey = keyPair.public as ECPublicKey
        val privateKey = keyPair.private as ECPrivateKey

        // Encode public key as uncompressed point (65 bytes: 0x04 + 32 bytes X + 32 bytes Y)
        val pubBytes = ByteArray(65)
        pubBytes[0] = 0x04
        val xBytes = publicKey.w.affineX.toByteArray()
        val yBytes = publicKey.w.affineY.toByteArray()
        // Handle potential leading zero byte for positive BigInteger
        System.arraycopy(xBytes, maxOf(0, xBytes.size - 32), pubBytes, 1 + maxOf(0, 32 - xBytes.size), minOf(32, xBytes.size))
        System.arraycopy(yBytes, maxOf(0, yBytes.size - 32), pubBytes, 33 + maxOf(0, 32 - yBytes.size), minOf(32, yBytes.size))

        // Encode private key as 32-byte scalar
        val sBytes = privateKey.s.toByteArray()
        val privBytes = ByteArray(32)
        System.arraycopy(sBytes, maxOf(0, sBytes.size - 32), privBytes, maxOf(0, 32 - sBytes.size), minOf(32, sBytes.size))

        val encoder = Base64.getUrlEncoder().withoutPadding()
        return encoder.encodeToString(pubBytes) to encoder.encodeToString(privBytes)
    }

    /**
     * Result of a push send operation.
     */
    sealed class SendResult {
        object Success : SendResult()
        object Expired : SendResult()  // Subscription expired, should be removed
        object NotConfigured : SendResult()
        data class Failed(val statusCode: Int, val message: String) : SendResult()
        data class Error(val exception: Exception) : SendResult()

        val isSuccess: Boolean get() = this is Success
        val shouldRemove: Boolean get() = this is Expired
    }
}
