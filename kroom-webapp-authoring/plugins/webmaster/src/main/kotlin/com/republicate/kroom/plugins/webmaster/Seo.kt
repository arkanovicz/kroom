package com.republicate.kroom.plugins.webmaster

import com.republicate.kroom.webapp.authoring.Site
import com.republicate.kroom.webapp.authoring.htmlEscape
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * `$seo` in a page: what this one request says of itself, before the head is rendered — `$seo.title(…)`,
 * `$seo.description(…)`, `$seo.image(…)`, `$seo.noindex()` — over the site-wide settings. Each renders nothing.
 */
class SeoPage internal constructor() {
    internal var title: String? = null
    internal var description: String? = null
    internal var image: String? = null
    internal var indexed = true

    fun title(value: String) = "".also { title = value }
    fun description(value: String) = "".also { description = value }
    fun image(value: String) = "".also { image = value }
    fun noindex() = "".also { indexed = false }
}

/**
 * What search engines and share previews read: description and Open Graph tags in every head, `robots.txt`,
 * and a `sitemap.xml` of every page the site serves — placeholder pages included, one URL per page their
 * blocks say exists ([Site.pages]). Titles stay the layout's: they are the page's words.
 */
internal fun Webmaster.installSeo(site: Site) {
    val settings = site.settings(this)

    site.requestTool("seo") { SeoPage() }

    site.head { call ->
        val page = site.requestValue("seo", call) as SeoPage
        val base = baseUrl(site)
        val tags = ArrayList<String>()
        fun meta(attribute: String, key: String, value: String?) {
            if (!value.isNullOrBlank()) tags += """<meta $attribute="$key" content="${htmlEscape(value.trim())}">"""
        }
        val description = page.description ?: settings["description"]
        meta("name", "description", description)
        meta("property", "og:site_name", settings["siteName"])
        meta("property", "og:title", page.title)
        meta("property", "og:description", description)
        meta("property", "og:image", page.image ?: settings["image"])
        if (base.isNotEmpty()) {
            meta("property", "og:url", base + call.request.path())
            tags += """<link rel="canonical" href="${htmlEscape(base + call.request.path())}">"""
        }
        if (settings["indexed"] != "true" || !page.indexed) meta("name", "robots", "noindex, nofollow")
        tags.joinToString("\n")
    }

    site.routes {
        get("/robots.txt") {
            val base = baseUrl(site)
            val rules = if (settings["indexed"] == "true") "User-agent: *\nAllow: /\n" else "User-agent: *\nDisallow: /\n"
            val sitemap = if (base.isNotEmpty()) "Sitemap: $base/sitemap.xml\n" else ""
            call.respondText(rules + sitemap, ContentType.Text.Plain)
        }

        get("/sitemap.xml") {
            val base = baseUrl(site)
            val excluded = settings["exclude"].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val urls = site.pages().flatMap { it.urls }.filter { it !in excluded }.distinct().sorted()
            val xml = buildString {
                append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
                append("""<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">""").append('\n')
                urls.forEach { append("  <url><loc>").append(htmlEscape(base + it)).append("</loc></url>\n") }
                append("</urlset>\n")
            }
            call.respondText(xml, ContentType.Application.Xml)
        }
    }
}
