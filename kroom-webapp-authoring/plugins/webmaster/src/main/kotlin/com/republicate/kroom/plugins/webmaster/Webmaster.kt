package com.republicate.kroom.plugins.webmaster

import com.republicate.kroom.webapp.authoring.AdminEntry
import com.republicate.kroom.webapp.authoring.AdminTable
import com.republicate.kroom.webapp.authoring.Permissions
import com.republicate.kroom.webapp.authoring.Plugin
import com.republicate.kroom.webapp.authoring.Setting
import com.republicate.kroom.webapp.authoring.Site
import com.republicate.kroom.webapp.authoring.htmlEscape
import com.republicate.kroom.webapp.authoring.page
import com.republicate.kroom.webapp.core.respondError
import com.republicate.kroom.webapp.session.userSession
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * The site as seen from outside — by crawlers, and by whoever follows its links:
 *
 * - **robots and sitemap**: whether the site wants indexing at all (`robots.txt`, and a robots meta in every
 *   head; a page keeps itself out with `$page.noindex()`), and a `sitemap.xml` of every page the site serves —
 *   placeholder pages included, one URL per page their blocks say exists ([Site.pages]);
 * - **link check** ([installLinkCheck]): every page walked on a schedule, every link and picture tried.
 *
 * What the site says of itself (description, share tags, canonical URL) is the site's own card, rendered by
 * kroom from the site's settings; redirects and the 404s are the site's too. Both read the public URL there.
 *
 * [http] is how link check fetches — the network by default, a test hands its own.
 */
class Webmaster(internal val http: suspend (String) -> Answer = ::fetch) : Plugin {
    override val id = "webmaster"
    override val name = "Webmaster"
    override val description = "Robots and sitemap, broken links"
    override val settings = listOf(
        Setting.boolean("indexed", "Indexed by search engines", default = true, group = SEARCH),
        Setting.textarea("exclude", "Left out of the sitemap", default = "/login", help = "One route per line", group = SEARCH),
        Setting.boolean("external", "Check other sites' links too", default = true, group = LINKS),
        Setting.number("periodHours", "Every (hours)", default = 24, group = LINKS)
    )

    internal companion object {
        const val SEARCH = "Search engines"
        const val LINKS = "Link check"
        const val API = "/api/webmaster"
    }

    override fun install(site: Site) {
        val settings = site.settings(this)

        site.head { call ->
            if (settings["indexed"] != "true" || !site.page(call).indexed) """<meta name="robots" content="noindex, nofollow">""" else null
        }

        site.routes {
            get("/robots.txt") {
                val rules = if (settings["indexed"] == "true") "User-agent: *\nAllow: /\n" else "User-agent: *\nDisallow: /\n"
                val sitemap = if (site.baseUrl.isNotEmpty()) "Sitemap: ${site.baseUrl}/sitemap.xml\n" else ""
                call.respondText(rules + sitemap, ContentType.Text.Plain)
            }

            get("/sitemap.xml") {
                val excluded = settings["exclude"].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                val urls = site.pages().flatMap { it.urls }.filter { it !in excluded }.distinct().sorted()
                val xml = buildString {
                    append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
                    append("""<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">""").append('\n')
                    urls.forEach { append("  <url><loc>").append(htmlEscape(site.baseUrl + it)).append("</loc></url>\n") }
                    append("</urlset>\n")
                }
                call.respondText(xml, ContentType.Application.Xml)
            }
        }

        installLinkCheck(site)
        site.admin(AdminEntry("webmaster", "Webmaster", icon = "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM3 12h18M12 3c2.5 2.7 3.8 5.7 3.8 9s-1.3 6.3-3.8 9c-2.5-2.7-3.8-5.7-3.8-9S9.5 5.7 12 3",
            tables = listOf(AdminTable("broken", "Broken links", "$API/broken"))))
    }
}

/** For [Permissions.ADMIN] only; false having answered 403. */
internal suspend fun RoutingContext.admin(site: Site): Boolean =
    site.can(call.userSession, Permissions.ADMIN).also {
        if (!it) respondError("not an administrator", HttpStatusCode.Forbidden, "notAdmin")
    }
