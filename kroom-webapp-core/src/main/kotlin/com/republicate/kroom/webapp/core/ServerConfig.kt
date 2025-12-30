package com.republicate.kroom.webapp.core

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*

/**
 * Kroom server configuration for embedded Netty server.
 *
 * HTTP/2 cleartext (h2c) is enabled by default to support SSE connection
 * multiplexing when behind an HTTP/2-capable reverse proxy (e.g., haproxy).
 *
 * Usage:
 * ```kotlin
 * kroomServer {
 *     port = 8080
 *     host = "0.0.0.0"
 *     // h2c = false  // disable if needed
 * } {
 *     // Application module
 *     install(SSE)
 *     configureRouting()
 * }.start(wait = true)
 * ```
 */
class KroomServerConfig {
    var port: Int = 8080
    var host: String = "0.0.0.0"

    /**
     * Enable HTTP/2 cleartext (h2c) support.
     * Default: true
     *
     * This allows HTTP/2 without TLS, typically used when the server
     * runs behind a reverse proxy that handles TLS termination and
     * forwards traffic as h2c.
     *
     * Benefits:
     * - Multiplexed connections solve browser's 6-connection limit for SSE
     * - Lower latency for concurrent requests
     * - More efficient connection handling
     *
     * Note: h2c cannot be used with SSL connectors on the same server.
     */
    var h2c: Boolean = true
}

/**
 * Create and configure a Kroom server with sensible defaults.
 *
 * @param configure Configuration block for server settings
 * @param module Application module to install
 * @return Configured EmbeddedServer ready to start
 */
fun kroomServer(
    configure: KroomServerConfig.() -> Unit = {},
    module: Application.() -> Unit
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    val config = KroomServerConfig().apply(configure)

    return embeddedServer(
        factory = Netty,
        configure = {
            connector {
                port = config.port
                host = config.host
            }
            if (config.h2c) {
                enableHttp2 = true
                enableH2c = true
            }
        },
        module = module
    )
}
