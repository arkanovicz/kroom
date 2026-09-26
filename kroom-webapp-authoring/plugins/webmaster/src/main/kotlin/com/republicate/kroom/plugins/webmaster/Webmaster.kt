package com.republicate.kroom.plugins.webmaster

import com.republicate.kroom.webapp.authoring.AdminEntry
import com.republicate.kroom.webapp.authoring.AdminTable
import com.republicate.kroom.webapp.authoring.Permissions
import com.republicate.kroom.webapp.authoring.Plugin
import com.republicate.kroom.webapp.authoring.Setting
import com.republicate.kroom.webapp.authoring.Site
import com.republicate.kroom.webapp.core.respondError
import com.republicate.kroom.webapp.core.respondJson
import com.republicate.kroom.webapp.session.userSession
import com.republicate.kson.Json
import io.ktor.http.*
import io.ktor.server.routing.*

/**
 * The health of the site's URLs, in one plugin because it is one concern seen from three sides:
 *
 * - **search engines** ([installSeo]): description and Open Graph tags in every head, a page's own via `$seo`,
 *   `robots.txt`, `sitemap.xml`;
 * - **redirects** ([installRedirects]): moved URLs answered before routing, and what nothing answered, counted;
 * - **link check** ([installLinkCheck]): every page walked on a schedule, every link and picture tried.
 *
 * They share the site's public URL, and one admin entry whose three tables are the webmaster's to-do list.
 * Audience counting stays apart (the analytics plugin): it is about visitors' privacy, not URLs, and its
 * provider is swapped on its own.
 *
 * [resolvers] map what only the application knows to today's URLs (`@player` in a rule); [http] is how link
 * check fetches — the network by default, a test hands its own.
 */
class Webmaster(
    internal val resolvers: Map<String, (Map<String, String>) -> String?> = emptyMap(),
    internal val http: suspend (String) -> Answer = ::fetch
) : Plugin {
    override val id = "webmaster"
    override val name = "Webmaster"
    override val description = "Search engines, redirects and 404s, broken links"
    override val settings = listOf(
        Setting.text("baseUrl", "Public URL", help = "https://example.org — absolute URLs (og:url, sitemap), and where link check reads the site"),
        Setting.text("siteName", "Site name", help = "og:site_name", group = SEARCH),
        Setting.textarea("description", "Description", help = "What a search result says under the title", group = SEARCH),
        Setting.text("image", "Share image", help = "Absolute URL of the picture a shared link shows", group = SEARCH),
        Setting.boolean("indexed", "Indexed by search engines", default = true, group = SEARCH),
        Setting.textarea("exclude", "Left out of the sitemap", default = "/login", help = "One route per line", group = SEARCH),
        Setting.textarea("rules", "Rules", group = REDIRECTS,
            help = "One per line: /from /to [301|302|307|308] — a trailing * on both sides, ?id={id}, @resolver"),
        Setting.boolean("external", "Check other sites' links too", default = true, group = LINKS),
        Setting.number("periodHours", "Every (hours)", default = 24, group = LINKS)
    )

    internal companion object {
        const val SEARCH = "Search engines"
        const val REDIRECTS = "Redirects"
        const val LINKS = "Link check"
        const val API = "/api/webmaster"
    }

    internal fun baseUrl(site: Site) = site.settings(this)["baseUrl"]?.trim()?.trimEnd('/').orEmpty()

    override fun install(site: Site) {
        installSeo(site)
        installRedirects(site)
        installLinkCheck(site)
        site.admin(AdminEntry("webmaster", "Webmaster", icon = "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM3 12h18M12 3c2.5 2.7 3.8 5.7 3.8 9s-1.3 6.3-3.8 9c-2.5-2.7-3.8-5.7-3.8-9S9.5 5.7 12 3",
            tables = listOf(
                AdminTable("redirects", "Redirects", "$API/redirects"),
                AdminTable("missing", "Not found", "$API/missing"),
                AdminTable("broken", "Broken links", "$API/broken")
            )))
    }
}

/** For [Permissions.ADMIN] only; false having answered 403. */
internal suspend fun RoutingContext.admin(site: Site): Boolean =
    site.can(call.userSession, Permissions.ADMIN).also {
        if (!it) respondError("not an administrator", HttpStatusCode.Forbidden, "notAdmin")
    }

internal suspend fun RoutingContext.respondTable(columns: List<String>, rows: List<List<Any?>>) = respondJson {
    set("columns", Json.MutableArray().apply { columns.forEach { push(it) } })
    set("rows", Json.MutableArray().apply { rows.forEach { row -> push(Json.MutableArray().apply { row.forEach { push(it) } }) } })
}
