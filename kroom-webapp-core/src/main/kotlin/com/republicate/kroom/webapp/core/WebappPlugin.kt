package com.republicate.kroom.webapp.core

import io.ktor.server.application.*
import io.ktor.server.routing.*

/**
 * Interface for kroom webapp plugins.
 *
 * Each plugin provides:
 * - Ktor Application installation
 * - Route mounting
 */
interface WebappPlugin {
    val name: String

    /**
     * Install this plugin into the Ktor application.
     */
    fun Application.install()

    /**
     * Mount routes for this plugin.
     */
    fun Route.mountRoutes() {}
}
