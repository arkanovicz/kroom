package com.republicate.kroom.webapp.authoring

/**
 * What a site gains without its layouts being rewritten: SEO tags, a contact form, redirects, analytics.
 *
 * A plugin is one object, registered by the application (`installContentSite { plugins += Seo() }`). Its [id]
 * namespaces everything it owns — its settings and records in the [Storage], its permissions (`forms.read`),
 * its admin API — so two plugins never meet by accident. [install] runs once, before the engines are built,
 * and only *registers* on the [Site]: tools, routes, head and foot fragments, admin entries, listeners, jobs.
 * Anything it serves later reads the site's services then — settings are read per call, never copied.
 *
 * The shape follows what WordPress's most installed plugins actually hook into, ranked by how many
 * categories need each: admin UI and settings, owned storage, HTTP endpoints, head/foot injection, scheduled
 * jobs, block-side calls (shortcodes), roles, early request interception (redirects, firewall, cache).
 */
interface Plugin {
    /** `[a-z0-9_-]+`: the storage namespace, the permission prefix, the admin API segment. */
    val id: String

    val name: String get() = id

    val description: String get() = ""

    /** What an admin may set, in this order — the admin bar builds its form from it. */
    val settings: List<Setting> get() = emptyList()

    fun install(site: Site)
}

/**
 * One configurable value. Its [default] applies until an admin sets it; a [Type.SECRET] is never read back; a
 * [Type.CHOICE] is one of [choices].
 */
data class Setting(
    val key: String,
    val label: String = key,
    val default: String = "",
    val type: Type = Type.TEXT,
    val help: String? = null,
    val choices: List<String> = emptyList()
) {
    enum class Type { TEXT, TEXTAREA, BOOLEAN, NUMBER, SECRET, CHOICE }
}

/**
 * An entry of the admin bar: a link ([href]), a [table] — an API answering `{columns, rows}` — or a [frame],
 * another application's page shown in the panel (a webmail, a dashboard).
 */
data class AdminEntry(
    val id: String,
    val label: String,
    /** One SVG path on a 24px grid, stroked like the editor's pictograms; null shows the label's initial. */
    val icon: String? = null,
    val href: String? = null,
    val table: String? = null,
    val frame: String? = null,
    val permission: String = Permissions.ADMIN
)
