package com.republicate.kroom.webapp.authoring

import com.republicate.kroom.webapp.assets.kroomAssets
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

    // one tree in dev, laid out as the classpath production reads: `<devDir>/templates`, `<devDir>/static`
    installCore { static { devMode = config.devDir != null; devDir = config.devDir?.resolve("static") } }
    if (config.sessions) installSessions { config.sessionSecret?.let { sessionSecret = it } }

    installVelocity {
        templatePath = config.templatePath
        devMode = config.devDir != null
        devDir = config.devDir?.let { dir -> config.templatePath?.let { dir.resolve(it) } ?: dir }
        // the `%` stack: blocks read through the very store the editor writes to
        properties["markdown.loader"] = config.store
        properties["markdown.block.wrapper"] = config.wrapper
        config.placeholder?.let { properties["markdown.missing"] = it }
        if (config.blockTools.isNotEmpty()) properties["markdown.tools"] = config.blockTools.joinToString(",")
        properties.putAll(config.velocityProperties)
    }

    installAuthoring {
        store = config.store
        lockTimeout = config.lockTimeout
        canEdit = config.canEdit
        placeholder = config.placeholder
        strings.putAll(config.strings)
    }

    routing {
        kroomAssets()                 // /js/kroom/*: the house stack authoring.js builds on
        placeholderPages(config.pagePrefix, config.pageExtension)
        pages(config.pagePrefix, config.pageExtension)
    }
}

class ContentSiteConfig {
    /** Where the blocks live, read and written. */
    var store: ResourceStore = MemoryResourceStore()

    /** Classpath root of the `#` layouts; null keeps them at the classpath root. */
    var templatePath: String? = "templates"

    /**
     * A source resources directory (`src/main/resources`) to serve from instead of the classpath, hot-reloaded:
     * the layouts under its [templatePath], the static files under its `static/` — dev only.
     */
    var devDir: File? = null

    var pagePrefix: String = "pages"
    var pageExtension: String = "html"

    /** The template decorating each block; kroom's own ships in this module. */
    var wrapper: String = "kroom/block-wrapper.html"

    /**
     * Page-context tools every block may use, by name. A block sees nothing else of the page but what the
     * page passes it: `#markdown("description", {"club": $club})`.
     */
    val blockTools = mutableListOf<String>()

    /** What a page shows where a block has not been written yet (`%` markdown, `$name` in scope). */
    var placeholder: String? = null

    /** The editor's words, over its English ones — see [AuthoringConfig.strings]. */
    val strings = LinkedHashMap<String, String>()

    var lockTimeout: Duration = 2.minutes
    var canEdit: (UserSession?, String) -> Boolean = { session, _ -> session != null }

    var sessions: Boolean = true
    var sessionSecret: String? = null

    /** Applied last over everything above — the open door. */
    val velocityProperties = LinkedHashMap<String, Any?>()
}
