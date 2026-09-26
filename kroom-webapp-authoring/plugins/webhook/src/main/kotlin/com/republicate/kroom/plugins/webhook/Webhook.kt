package com.republicate.kroom.plugins.webhook

import com.republicate.kroom.webapp.authoring.Plugin
import com.republicate.kroom.webapp.authoring.Setting
import com.republicate.kroom.webapp.authoring.Site
import com.republicate.kson.Json
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val logger = LoggerFactory.getLogger("kroom.plugins.webhook")

/**
 * Tells another system each time a block is published — a static-site rebuild, a chat channel, a social
 * crossposter, an automation hub. One JSON POST per publish, `{path, rev, author, time}`, signed with the
 * secret as `X-Kroom-Signature: sha256=<hex hmac of the body>` so the receiver can tell it came from here.
 * Off the request, as every publish listener is: a slow receiver delays no author.
 */
class Webhook : Plugin {
    override val id = "webhook"
    override val name = "Webhook"
    override val description = "A signed POST to an URL of yours on each publish"
    override val settings = listOf(
        Setting.text("url", "Receiver URL", help = "Nothing is sent while empty"),
        Setting.secret("secret", "Signing secret")
    )

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    override fun install(site: Site) {
        val settings = site.settings(this)
        site.onPublish { block, author ->
            val url = settings["url"]?.trim().orEmpty().takeIf { it.isNotEmpty() } ?: return@onPublish
            val body = Json.MutableObject().apply {
                set("path", block.path); set("rev", block.rev); set("author", author.id); set("time", block.updated)
            }.toString()
            val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .apply { settings["secret"]?.takeIf { it.isNotEmpty() }?.let { header("X-Kroom-Signature", "sha256=" + sign(it, body)) } }
                .POST(HttpRequest.BodyPublishers.ofString(body)).build()
            val status = http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).await().statusCode()
            if (status >= 300) logger.warn("webhook {} answered {} for {}", url, status, block.path)
        }
    }

    companion object {
        fun sign(secret: String, body: String): String =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
                .doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
