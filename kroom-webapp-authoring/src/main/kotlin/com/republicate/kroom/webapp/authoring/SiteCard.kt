package com.republicate.kroom.webapp.authoring

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path

/**
 * `$page` in a page: what this one request says of itself, before the head is rendered — `$page.title(…)`,
 * `$page.description(…)`, `$page.image(…)`, `$page.noindex()` — over the site's own card. Each renders nothing.
 * The shape a page record will carry once pages are authored.
 */
class PageMeta internal constructor() {
    var title: String? = null
        internal set
    var description: String? = null
        internal set
    var image: String? = null
        internal set
    var indexed = true
        internal set

    fun title(value: String) = "".also { title = value }
    fun description(value: String) = "".also { description = value }
    fun image(value: String) = "".also { image = value }
    fun noindex() = "".also { indexed = false }
}

/** What this request's page said of itself so far. */
fun Site.page(call: ApplicationCall): PageMeta = requestValue("page", call) as PageMeta

/**
 * The site's card, in every head: description, Open Graph tags, canonical URL — the site's settings, a page's
 * own words over them. Whoever reads it (a search engine, a chat's link preview) is the reader's business;
 * whether the site wants to be indexed at all is the webmaster plugin's.
 */
internal fun Site.installCard() {
    requestTool("page") { PageMeta() }
    head { call ->
        val page = page(call)
        val settings = settings()
        val tags = ArrayList<String>()
        fun meta(attribute: String, key: String, value: String?) {
            if (!value.isNullOrBlank()) tags += """<meta $attribute="$key" content="${htmlEscape(value.trim())}">"""
        }
        val description = page.description ?: settings["description"]
        meta("name", "description", description)
        meta("property", "og:site_name", settings["name"])
        meta("property", "og:title", page.title)
        meta("property", "og:description", description)
        meta("property", "og:image", page.image ?: settings["image"])
        if (baseUrl.isNotEmpty()) {
            meta("property", "og:url", baseUrl + call.request.path())
            tags += """<link rel="canonical" href="${htmlEscape(baseUrl + call.request.path())}">"""
        }
        tags.joinToString("\n")
    }
}
