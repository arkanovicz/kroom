package com.republicate.kroom.webapp.authoring

import com.republicate.kroom.webapp.session.userSession
import com.republicate.kroom.webapp.velocity.velocity
import com.republicate.kson.Json
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.response.respondText

/**
 * A page an editor made, not a developer: a record — where it is, how it is laid out, what it says of itself,
 * whether it is out — and blocks, one per region, in the content store where a template's would be
 * (`pages/company/history/content.md`), edited in place like any other. A single template renders them all.
 * A region is what its block makes it: written, everyone sees it; not written, whoever may write it finds a
 * sliver there with the edit handle (the site's default, for the regions that have one), and a visitor nothing.
 */
data class AuthoredPage(
    /** `/company/history`: the slug tree, as in the menu. */
    val path: String,
    val layout: String = "default",
    val title: Map<String, String> = emptyMap(),
    val description: Map<String, String> = emptyMap(),
    val status: String = DRAFT,
    val author: String? = null,
    val created: Long = System.currentTimeMillis(),
    val published: Long? = null
) {
    val isPublished: Boolean get() = status == PUBLISHED

    /** The template path a block binds under: what `pages/company/history.html` would have been. */
    val template: String get() = "pages$path.html"

    fun title(lang: String, fallback: String) = title[lang] ?: title[fallback] ?: title.values.firstOrNull() ?: path.substringAfterLast('/')
    fun description(lang: String, fallback: String) = description[lang] ?: description[fallback]

    fun toJson(): Json.MutableObject = Json.MutableObject().apply {
        set("path", path)
        set("layout", layout)
        set("title", Json.MutableObject().apply { title.forEach { (k, v) -> set(k, v) } })
        set("description", Json.MutableObject().apply { description.forEach { (k, v) -> set(k, v) } })
        set("status", status)
        author?.let { set("author", it) }
        set("created", created)
        published?.let { set("published", it) }
    }

    companion object {
        const val DRAFT = "draft"
        const val PUBLISHED = "published"
        private val PATH = Regex("(/[A-Za-z0-9_-]+)+")

        fun validPath(path: String) = PATH.matches(path)

        /** The record from its JSON, [existing] supplying what the JSON leaves out; or why it is not one. */
        fun parse(json: Json.Object, existing: AuthoredPage? = null): Result<AuthoredPage> = runCatching {
            // a page does not move: what exists keeps its path, whatever the JSON says
            val path = existing?.path ?: json.getString("path") ?: throw IllegalArgumentException("path expected")
            require(validPath(path)) { "not a page path: $path" }
            val layout = json.getString("layout") ?: existing?.layout ?: "default"
            require(layout in SKELETON_LAYOUTS) { "not a layout: $layout" }
            val status = json.getString("status") ?: existing?.status ?: DRAFT
            require(status == DRAFT || status == PUBLISHED) { "not a status: $status" }
            fun words(key: String) = json.getObject(key)?.entries?.associate { (k, v) -> k to v.toString() }
            AuthoredPage(
                path = path, layout = layout,
                title = words("title") ?: existing?.title.orEmpty(),
                description = words("description") ?: existing?.description.orEmpty(),
                status = status, author = existing?.author, created = existing?.created ?: System.currentTimeMillis(),
                published = if (status == PUBLISHED) existing?.published ?: System.currentTimeMillis() else null
            )
        }
    }
}

/** kroom-markdown's `MarkdownMacro.PAGE`: the context key naming the page a block binds under (no dependency, as for `markdown.*`). */
internal const val MARKDOWN_PAGE = "kroomPage"

/** The authored pages, keyed by path in the site's records. */
internal class AuthoredPages(private val site: Site) {

    private val records get() = site.storage.records(SITE, "pages")

    private fun id(path: String) = path.trim('/').replace('/', '~')

    fun get(path: String): AuthoredPage? = records.get(id(path))?.let { AuthoredPage.parse(it).getOrNull() }

    fun all(): List<AuthoredPage> = records.list(Int.MAX_VALUE).values.mapNotNull { AuthoredPage.parse(it).getOrNull() }.sortedBy { it.path }

    fun put(page: AuthoredPage) = records.put(id(page.path), page.toJson())

    fun delete(path: String): Boolean = records.delete(id(path))

    /** Published paths — what visitors, the menu and the sitemap see. */
    fun published(): List<String> = all().filter { it.isPublished }.map { it.path }

    /** Whether [session] may see [page]: anyone once it is published, its editors before. */
    fun visible(page: AuthoredPage, call: ApplicationCall): Boolean =
        page.isPublished || site.can(call.userSession, Permissions.PAGE_EDIT, page.path)

    /**
     * Answer the request with the authored page at its path, if there is one the caller may see — the
     * site's `notFound` handler, so a developer's page always wins.
     */
    suspend fun serve(call: ApplicationCall): Boolean {
        val (template, model) = resolve(call, call.request.path()) ?: return false
        call.respondText(site.application.velocity.renderForCall(call, template, model), ContentType.Text.Html)
        return true
    }

    /** The authored page at [url] this caller may see, as the template rendering it and what it is rendered with. */
    fun resolve(call: ApplicationCall, url: String): Pair<String, Map<String, Any?>>? {
        val path = url.substringBefore('?').trimEnd('/').ifEmpty { "/" }
        val page = get(path)?.takeIf { visible(it, call) } ?: return null
        return "kroom/page.html" to mapOf(
            MARKDOWN_PAGE to page.template,
            "authored" to AuthoredPageView(page, site, call)
        )
    }
}

/** `$authored` in `kroom/page.html`: the record in the request's language, and what each region is to this viewer. */
class AuthoredPageView internal constructor(private val page: AuthoredPage, private val site: Site, private val call: ApplicationCall) {
    val path: String get() = page.path
    val layout: String get() = page.layout
    val title: String = page.title(site.language(call), site.defaultLanguage)
    val description: String? = page.description(site.language(call), site.defaultLanguage)
    val draft: Boolean get() = !page.isPublished

    private fun block(region: String) = "pages${page.path}/$region.md"

    /** Whether someone wrote this region's block. */
    fun written(region: String): Boolean = site.storage.content.read(block(region)) != null

    /** Whether this viewer may write this page's blocks — and so sees its unwritten regions as slivers to hover. */
    val editable: Boolean get() = site.can(call.userSession, Permissions.EDIT, block("content"))
}
