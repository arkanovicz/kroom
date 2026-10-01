package com.republicate.kroom.webapp.authoring

import com.republicate.kson.Json

/**
 * One entry of the menu as stored: a page, named by its [slug] — the segment under its parent, so the tree of
 * slugs IS the tree of URLs (`company` under the root is `/company`, `history` under it `/company/history`;
 * `company.html` and `company/index.html` are one page) — or an address elsewhere ([href]). [label] and
 * [description] by language, the site's default filling what a language lacks.
 */
data class MenuItem(
    val slug: String? = null,
    val href: String? = null,
    val label: Map<String, String> = emptyMap(),
    val description: Map<String, String> = emptyMap(),
    val children: List<MenuItem> = emptyList()
) {
    val external: Boolean get() = slug == null

    /**
     * The entry a theme sees, in [lang] (then [fallback]), its path under [parent], as [resolve] answers for
     * that path: a published page, a draft the viewer may see, or nothing — a draft is kept and marked, an
     * entry nobody may see is dropped (null).
     */
    fun view(lang: String, fallback: String, parent: String, resolve: (String) -> PageState): NavItem? {
        val path = if (external) href.orEmpty() else "$parent/$slug"
        val state = if (external) PageState.PUBLISHED else resolve(path)
        if (state == PageState.FORBIDDEN) return null
        val children = children.mapNotNull { it.view(lang, fallback, path, resolve) }
        return NavItem(
            label = label[lang] ?: label[fallback] ?: label.values.firstOrNull() ?: slug?.replace('-', ' ')?.replaceFirstChar(Char::titlecase) ?: path,
            href = path,
            description = description[lang] ?: description[fallback],
            external = external,
            children = children,
            slug = slug,
            resolved = state != PageState.MISSING,
            draft = state == PageState.DRAFT,
            link = if (state != PageState.MISSING) path else children.firstNotNullOfOrNull { it.link }
        )
    }

    fun toJson(): Json.MutableObject = Json.MutableObject().apply {
        slug?.let { set("slug", it) }
        href?.let { set("href", it) }
        set("label", Json.MutableObject().apply { label.forEach { (k, v) -> set(k, v) } })
        if (description.isNotEmpty()) set("description", Json.MutableObject().apply { description.forEach { (k, v) -> set(k, v) } })
        if (children.isNotEmpty()) set("children", Json.MutableArray().apply { children.forEach { push(it.toJson()) } })
    }

    companion object {
        private val SLUG = Regex("[A-Za-z0-9_-]+")

        /** An entry from its JSON, or the reason it is not one. */
        fun parse(json: Json.Object): Result<MenuItem> = runCatching {
            val slug = json.getString("slug")?.takeIf { it.isNotEmpty() }
            val href = json.getString("href")?.takeIf { it.isNotEmpty() }
            require((slug == null) != (href == null)) { "an entry is a page (slug) or a link (href), not both nor neither" }
            require(slug == null || SLUG.matches(slug)) { "not a page segment: $slug" }
            require(href == null || href.startsWith("https://") || href.startsWith("http://")) { "not an address: $href" }
            fun words(key: String) = json.getObject(key)?.entries?.associate { (k, v) -> k to v.toString() }.orEmpty()
            val children = json.getArray("children")?.map { parse(it as Json.Object).getOrThrow() }.orEmpty()
            require(href == null || children.isEmpty()) { "a link has no children" }
            MenuItem(slug, href, words("label"), words("description"), children)
        }

        fun parseAll(json: Json.Array): Result<List<MenuItem>> = runCatching { json.map { parse(it as Json.Object).getOrThrow() } }

        /**
         * A menu from the pages a site serves: one entry per URL, nested by segment, a section made for a
         * segment no page answers (and marked unresolved) — what a site without a menu of its own shows.
         */
        fun derive(urls: List<String>, lang: String): List<MenuItem> {
            class Node(val slug: String) { val children = LinkedHashMap<String, Node>() }
            val root = Node("")
            urls.filter { it != "/index" && it != "/" }.forEach { url ->
                var node = root
                url.trimStart('/').split('/').filter { it.isNotEmpty() }.forEach { node = node.children.getOrPut(it) { Node(it) } }
            }
            fun item(node: Node): MenuItem = MenuItem(
                slug = node.slug,
                label = mapOf(lang to node.slug.replace('-', ' ').replaceFirstChar(Char::titlecase)),
                children = node.children.values.map(::item)
            )
            return root.children.values.map(::item)
        }
    }
}

/** What a path is to a viewer: a page out for all, a draft this viewer may see, a draft they may not, or no page. */
enum class PageState { PUBLISHED, DRAFT, FORBIDDEN, MISSING }

/** One entry of the menu as a theme sees it: words in the request's language, a path, children. */
data class NavItem(
    val label: String,
    val href: String,
    val description: String? = null,
    val external: Boolean = false,
    val children: List<NavItem> = emptyList(),
    /** The page's segment; null for an address elsewhere. */
    val slug: String? = null,
    /** Whether a page answers [href] — a section nobody wrote leads to its first page ([link]), or is only words. */
    val resolved: Boolean = true,
    /** A page not out yet, shown because this viewer may edit it. */
    val draft: Boolean = false,
    /** Where a click leads: the page itself, or — for a section nobody wrote — its first page; null when neither. */
    val link: String? = href
)

/** `$nav` in a layout: the menu, and where in it this request is. */
class Navigation(val items: List<NavItem>, path: String) {
    /** From the top item down to the one this request is at; empty when it is at none. */
    val trail: List<NavItem> = items.firstNotNullOfOrNull { trail(it, path) }.orEmpty()

    /** The top-level entry the visitor is under — whose pages the west region lists. */
    val section: NavItem? get() = trail.firstOrNull()

    val here: NavItem? get() = trail.lastOrNull()

    fun contains(item: NavItem): Boolean = item in trail

    private fun trail(item: NavItem, path: String): List<NavItem>? =
        if (item.href == path) listOf(item)
        else item.children.firstNotNullOfOrNull { trail(it, path) }?.let { listOf(item) + it }
}
