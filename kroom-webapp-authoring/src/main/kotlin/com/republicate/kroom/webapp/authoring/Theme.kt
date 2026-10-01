package com.republicate.kroom.webapp.authoring

/**
 * How a site looks: layouts a page asks for by name, around the regions it defines.
 *
 * A page says what it is and hands its parts down as closures — Velocity's `#define`, rendered where the
 * layout places them, straight to the response — then names its layout:
 *
 * ```velocity
 * #set($title = "Les Vagabonds")
 * #define($east) <article>…</article> #end
 * #define($content) <h1>…</h1> #markdown("description") #end
 * #layout("sidebar")
 * ```
 *
 * The regions: `$header` and `$footer` (the site's own when the page defines none), `$top` (under the header),
 * `$content`, `$east`, `$west` (the site's own — the section's pages — when the page defines none). The
 * layouts: `default`, `article`, `sidebar`, `landing`; `#layout()` takes the site's `layout` setting.
 *
 * Two kinds of theme. A **[Skin]** names its stylesheets (and scripts) and renders kroom's own skeleton,
 * `kroom/skeleton.html`, every layout of the contract in one file, each region through a partial the skin may
 * rewrite (`themes/<id>/regions/<region>.html` over `kroom/regions/<region>.html`). A **markup-owning** theme
 * provides its [layouts] under `themes/<id>/layouts/<name>.html` — `default` required, the others when it has
 * them (a missing one falls back to `default`) — rendering `$content` and the regions it shows (`#if($east)`
 * tests without rendering), `$title`, `$site.name` and friends, `$site.head()` at the end of `<head>`,
 * `$site.foot()` at the end of `<body>` — all kroom and its plugins ask; `$nav` for the menu (`#nav($nav.items)`
 * renders the tree); `$theme` for itself: `id`, `name`, `stylesheets`, `scripts`, `settings`, and any setting
 * by key (`$theme.scheme`). Private partials under `themes/<id>/inc/`, assets under `static/{css,js,img,fonts}/<id>/`.
 * A theme jar keeps its templates at the classpath root; an application's own theme lives in its templates
 * directory. A theme adds to `<head>` as any plugin does, `site.head { }` in its [install]: kroom emits it
 * while the theme is the active one.
 *
 * Several themes may be installed; one is active — the `theme` site setting, switched from the admin bar,
 * live — and an admin previews another with `?theme=<id>`.
 */
interface Theme : Plugin {
    /** What it provides, `default` among them. */
    val layouts: Set<String>

    override fun install(site: Site) {}
}

/** A theme that is only a look: stylesheets (and scripts) over kroom's skeleton, which renders every layout. */
interface Skin : Theme {
    val stylesheets: List<String>
    val scripts: List<String> get() = emptyList()
    override val layouts: Set<String> get() = SKELETON_LAYOUTS
}

/** The layouts kroom's skeleton renders — the contract's vocabulary. */
val SKELETON_LAYOUTS: Set<String> = setOf("default", "article", "sidebar", "landing")

/** `$theme` in a layout: the active theme, and its settings by key (`$theme.scheme`). */
class ThemeView internal constructor(private val theme: Theme, private val values: Settings) {
    val id: String get() = theme.id
    val name: String get() = theme.name
    val layouts: Set<String> get() = theme.layouts
    val stylesheets: List<String> get() = (theme as? Skin)?.stylesheets.orEmpty()
    val scripts: List<String> get() = (theme as? Skin)?.scripts.orEmpty()
    /** Every setting by key, defaults showing through. */
    val settings: Map<String, String> get() = values.all()
    /** A setting by key — what `$theme.scheme` reads. */
    fun get(key: String): String? = values[key]
}

/** kroom's own look: pico over the skeleton — what a site wears until it has a theme of its own. */
class BasicTheme : Skin {
    override val id = "basic"
    override val name = "Basic"
    override val description = "pico over kroom's skeleton — the look of a site without a theme of its own"
    override val stylesheets = listOf("/lib/pico/pico.min.css", "/css/basic/theme.css")
    override val settings = listOf(Setting.choice("scheme", "Colours", choices = listOf("auto", "light", "dark")))
}
