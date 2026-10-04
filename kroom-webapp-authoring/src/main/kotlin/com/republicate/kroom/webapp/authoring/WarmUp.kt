package com.republicate.kroom.webapp.authoring

import io.ktor.server.application.Application
import io.ktor.server.application.ServerReady
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val logger = LoggerFactory.getLogger("kroom.site")

/**
 * Once the server listens, it asks itself for `/`, in the background: the first visitor would otherwise pay
 * what interpreted templates cost the first time — the runtime compiler's start, both engines' (the pages' and
 * the blocks'), the home page's own compile — several seconds. A real request runs the whole way (layout, regions,
 * blocks), which no synthetic render would. Skipped under a test engine, which listens nowhere.
 */
internal fun Application.warmUp() {
    monitor.subscribe(ServerReady) {
        val engine = this@warmUp.engine
        if (engine.javaClass.name.contains("Test")) return@subscribe
        launch(Dispatchers.IO) {
            val connector = runCatching { engine.resolvedConnectors().firstOrNull { it.type.name == "HTTP" } }.getOrNull()
                ?: return@launch
            val host = connector.host.takeUnless { it == "0.0.0.0" || it == "::" } ?: "localhost"
            val start = System.currentTimeMillis()
            runCatching {
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                val request = HttpRequest.newBuilder(URI("http://$host:${connector.port}/")).timeout(Duration.ofMinutes(2)).GET().build()
                client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            }.onSuccess { status -> logger.info("warm-up: / answered {} in {} ms", status, System.currentTimeMillis() - start) }
             .onFailure { logger.warn("warm-up: / failed ({}); the first visitor pays it", it.message ?: it.javaClass.simpleName) }
        }
    }
}
