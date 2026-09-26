package com.republicate.kroom.plugins.analytics

import com.republicate.kroom.webapp.authoring.Plugin
import com.republicate.kroom.webapp.authoring.Setting
import com.republicate.kroom.webapp.authoring.Setting.Type.BOOLEAN
import com.republicate.kroom.webapp.authoring.Site
import com.republicate.kroom.webapp.authoring.htmlEscape
import com.republicate.kroom.webapp.session.userSession

/**
 * An audience counter's script in every page — Plausible's by default, any cookieless counter that loads as
 * `<script defer data-domain=… src=…>` otherwise, so no consent banner is owed. Nothing is emitted until a
 * domain is set; the site's own authors are not counted unless the admin says so.
 */
class Analytics : Plugin {
    override val id = "analytics"
    override val name = "Analytics"
    override val description = "A privacy-friendly audience counter (Plausible or alike)"
    override val settings = listOf(
        Setting("domain", "Counted domain", help = "example.org — nothing is emitted while empty"),
        Setting("script", "Script URL", default = "https://plausible.io/js/script.js"),
        Setting("countAuthors", "Count logged-in authors too", default = "false", type = BOOLEAN)
    )

    override fun install(site: Site) {
        val settings = site.settings(this)
        site.foot { call ->
            val domain = settings["domain"]?.trim().orEmpty()
            when {
                domain.isEmpty() -> null
                call.userSession != null && settings["countAuthors"] != "true" -> null
                else -> """<script defer data-domain="${htmlEscape(domain)}" src="${htmlEscape(settings["script"].orEmpty())}"></script>"""
            }
        }
    }
}
