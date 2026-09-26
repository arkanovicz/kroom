package com.republicate.kroom.plugins.seo

import com.republicate.kroom.webapp.authoring.Plugin
import com.republicate.kroom.webapp.authoring.Setting
import com.republicate.kroom.webapp.authoring.Setting.Type.BOOLEAN
import com.republicate.kroom.webapp.authoring.Setting.Type.TEXTAREA
import com.republicate.kroom.webapp.authoring.Site
import com.republicate.kroom.webapp.authoring.htmlEscape
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * What search engines and share previews read: description and Open Graph tags in every page's head, a
 * `robots.txt`, and a `sitemap.xml` listing every page the site serves — placeholder pages included, one
 * URL per page their blocks say exists ([Site.pages]). Titles stay the layout's: they are the page's words.
 */
class Seo : Plugin {
    override val id = "seo"
    override val name = "SEO"
    override val description = "Meta and Open Graph tags, robots.txt and sitemap.xml"
    override val settings = listOf(
        Setting("baseUrl", "Public base URL", help = "https://example.org — for absolute URLs: og:url, the sitemap"),
        Setting("siteName", "Site name", help = "og:site_name"),
        Setting("description", "Description", type = TEXTAREA, help = "What a search result says under the title"),
        Setting("image", "Share image", help = "Absolute URL of the picture a shared link shows"),
        Setting("indexed", "Indexed by search engines", default = "true", type = BOOLEAN),
        Setting("exclude", "Left out of the sitemap", default = "/login", type = TEXTAREA, help = "One route per line")
    )

    override fun install(site: Site) {
        val settings = site.settings(this)
        fun base() = settings["baseUrl"].orEmpty().trimEnd('/')

        site.head { call ->
            val tags = ArrayList<String>()
            fun meta(attribute: String, key: String, value: String?) {
                if (!value.isNullOrBlank()) tags += """<meta $attribute="$key" content="${htmlEscape(value.trim())}">"""
            }
            meta("name", "description", settings["description"])
            meta("property", "og:site_name", settings["siteName"])
            meta("property", "og:description", settings["description"])
            meta("property", "og:image", settings["image"])
            if (base().isNotEmpty()) {
                meta("property", "og:url", base() + call.request.path())
                tags += """<link rel="canonical" href="${htmlEscape(base() + call.request.path())}">"""
            }
            if (settings["indexed"] != "true") meta("name", "robots", "noindex, nofollow")
            tags.joinToString("\n")
        }

        site.routes {
            get("/robots.txt") {
                val rules = if (settings["indexed"] == "true") "User-agent: *\nAllow: /\n" else "User-agent: *\nDisallow: /\n"
                val sitemap = if (base().isNotEmpty()) "Sitemap: ${base()}/sitemap.xml\n" else ""
                call.respondText(rules + sitemap, ContentType.Text.Plain)
            }

            get("/sitemap.xml") {
                val excluded = settings["exclude"].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                val urls = site.pages().flatMap { it.urls }.filter { it !in excluded }.distinct().sorted()
                val xml = buildString {
                    append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
                    append("""<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">""").append('\n')
                    urls.forEach { append("  <url><loc>").append(htmlEscape(base() + it)).append("</loc></url>\n") }
                    append("</urlset>\n")
                }
                call.respondText(xml, ContentType.Application.Xml)
            }
        }
    }
}
