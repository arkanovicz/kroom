package com.republicate.kroom.webapp.authoring

/**
 * How a site looks: layouts a page asks for by name, around the regions it defines.
 *
 * A page says what it is and hands its parts down as closures — Velocity's `#define`, rendered where the
 * layout places them, straight to the response — then names its layout:
 *
 * ```velocity
 * #set($title = "Les Vagabonds")
 * #define($aside) <article>…</article> #end
 * #define($content) <h1>…</h1> #markdown("description") #end
 * #layout("sidebar")
 * ```
 *
 * The contract, for a theme: layouts under `themes/<id>/layouts/<name>.html` — `default` required, `article`,
 * `sidebar`, `landing` when it has them (a missing one falls back to `default`) — rendering `$content`, and
 * `$aside` / `$hero` when defined (`#if($aside)` tests without rendering); `$title`; `$site.name`, `$site.lang`, `$site.description` for what the site says of itself; `$site.head()` at the end of
 * `<head>`, `$site.foot()` at the end of `<body>` — all kroom and its plugins ask; `$nav` for the menu; `$theme`
 * for the theme's own settings. Private partials under `themes/<id>/inc/`, assets under
 * `static/{css,js,img,fonts}/<id>/`. A theme jar keeps its templates at the classpath root; an application's
 * own theme lives in its templates directory.
 *
 * Several themes may be installed; one is active — the `theme` site setting, switched from the admin bar,
 * live — and an admin previews another with `?theme=<id>`.
 */
interface Theme : Plugin {
    /** What it provides, `default` among them. */
    val layouts: Set<String>

    override fun install(site: Site) {}
}

/** One entry of the menu: a page, a section with its pages, or an address elsewhere. */
data class NavItem(
    val label: String,
    val href: String,
    val description: String? = null,
    val external: Boolean = false,
    val children: List<NavItem> = emptyList()
)

/** `$nav` in a layout: the menu, and where this request stands in it. */
class Navigation(val items: List<NavItem>, path: String) {
    /** From the top item down to the one this request is at; empty when it is at none. */
    val trail: List<NavItem> = items.firstNotNullOfOrNull { trail(it, path) }.orEmpty()

    val here: NavItem? get() = trail.lastOrNull()

    fun contains(item: NavItem): Boolean = item in trail

    private fun trail(item: NavItem, path: String): List<NavItem>? =
        if (item.href == path) listOf(item)
        else item.children.firstNotNullOfOrNull { trail(it, path) }?.let { listOf(item) + it }
}

/** kroom's own look: pico, a header with the menu, a footer — what a site wears until it has a theme of its own. */
class BasicTheme : Theme {
    override val id = "basic"
    override val name = "Basic"
    override val description = "pico, a menu, a footer — the look of a site without a theme of its own"
    override val layouts = setOf("default", "sidebar")
    override val settings = listOf(
        Setting.choice("scheme", "Colours", choices = listOf("auto", "light", "dark")),
        Setting.text("footer", "Footer")
    )
}
