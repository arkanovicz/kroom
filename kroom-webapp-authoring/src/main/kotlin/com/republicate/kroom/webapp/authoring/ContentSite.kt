package com.republicate.kroom.webapp.authoring

import com.republicate.kroom.webapp.core.installCore
import com.republicate.kroom.webapp.session.UserSession
import com.republicate.kroom.webapp.session.installSessions
import com.republicate.kroom.webapp.velocity.installVelocity
import com.republicate.kroom.webapp.velocity.pages
import com.republicate.kroom.webapp.velocity.placeholderPages
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A content site in one call: `#` layouts from the application's own resources, `%` markdown blocks from
 * [ContentSiteConfig.store], the edit API over them, and the pages routed — including the ones whose path
 * carries a placeholder (`pages/club/_code_.html` → `/club/{code}`).
 *
 * It is a starting point, not a frame: every piece it installs is a plain `installX` an application can
 * call itself, and [ContentSiteConfig.velocityProperties] is applied last, so any decision here can be
 * overridden rather than worked around.
 */
fun Application.installContentSite(block: ContentSiteConfig.() -> Unit = {}) {
    val config = ContentSiteConfig().apply(block)

    installCore()
    if (config.sessions) installSessions { config.sessionSecret?.let { sessionSecret = it } }

    installVelocity {
        templatePath = config.templatePath
        devMode = config.devDir != null
        devDir = config.devDir
        // the `%` stack: blocks read through the very store the editor writes to
        properties["markdown.resource.loaders"] = "content"
        properties["markdown.resource.loader.content.instance"] = config.store
        properties["markdown.block.wrapper"] = config.wrapper
        config.placeholder?.let { properties["markdown.missing"] = it }
        properties.putAll(config.velocityProperties)
    }

    installAuthoring {
        store = config.store
        lockTimeout = config.lockTimeout
        canEdit = config.canEdit
        placeholder = config.placeholder
    }

    routing {
        placeholderPages(config.pagePrefix, config.pageExtension)
        pages(config.pagePrefix, config.pageExtension)
    }
}

class ContentSiteConfig {
    /** Where the blocks live, read and written. */
    var store: ResourceStore = MemoryResourceStore()

    /** Classpath root of the `#` layouts; null keeps them at the classpath root. */
    var templatePath: String? = "templates"

    /** A source directory to serve layouts from instead, hot-reloaded — dev only. */
    var devDir: File? = null

    var pagePrefix: String = "pages"
    var pageExtension: String = "html"

    /** The template decorating each block; kroom's own ships in this module. */
    var wrapper: String = "kroom/block-wrapper.html"

    /** What a page shows where a block has not been written yet (`%` markdown, `$name` in scope). */
    var placeholder: String? = null

    var lockTimeout: Duration = 2.minutes
    var canEdit: (UserSession?, String) -> Boolean = { session, _ -> session != null }

    var sessions: Boolean = true
    var sessionSecret: String? = null

    /** Applied last over everything above — the open door. */
    val velocityProperties = LinkedHashMap<String, Any?>()
}
