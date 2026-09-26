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
 * One configurable value, made by the function that names its kind — `Setting.text("host")`,
 * `Setting.choice("security", choices = listOf("starttls", "tls", "none"))` — its [default] applying until an
 * admin sets one. Values are strings: a boolean is `true`/`false`, a number its digits. A secret is never read
 * back. [group] gathers settings under one heading, for a plugin with several concerns.
 */
class Setting private constructor(
    val key: String,
    val label: String,
    val default: String,
    /** `text`, `textarea`, `number`, `boolean`, `secret` or `choice` — what the admin form shows. */
    val type: String,
    val help: String?,
    val choices: List<String>,
    val group: String?
) {
    companion object {
        fun text(key: String, label: String = key, default: String = "", help: String? = null, group: String? = null) =
            Setting(key, label, default, "text", help, emptyList(), group)

        fun textarea(key: String, label: String = key, default: String = "", help: String? = null, group: String? = null) =
            Setting(key, label, default, "textarea", help, emptyList(), group)

        fun number(key: String, label: String = key, default: Long? = null, help: String? = null, group: String? = null) =
            Setting(key, label, default?.toString().orEmpty(), "number", help, emptyList(), group)

        fun boolean(key: String, label: String = key, default: Boolean = false, help: String? = null, group: String? = null) =
            Setting(key, label, default.toString(), "boolean", help, emptyList(), group)

        fun secret(key: String, label: String = key, help: String? = null, group: String? = null) =
            Setting(key, label, "", "secret", help, emptyList(), group)

        fun choice(key: String, label: String = key, choices: List<String>, default: String = choices.first(), help: String? = null, group: String? = null): Setting {
            require(default in choices) { "$key: $default is none of $choices" }
            return Setting(key, label, default, "choice", help, choices, group)
        }
    }
}

/**
 * An entry of the admin bar: a link ([href]), [tables] — APIs answering `{columns, rows}`, one under the other,
 * each under its label when there are several — or a [frame], another application's page shown in the panel
 * (a webmail, a dashboard).
 */
data class AdminEntry(
    val id: String,
    val label: String,
    /** One SVG path on a 24px grid, stroked like the editor's pictograms; null shows the label's initial. */
    val icon: String? = null,
    val href: String? = null,
    val tables: List<AdminTable> = emptyList(),
    val frame: String? = null,
    val permission: String = Permissions.ADMIN
)

/** A table of an admin entry: its label, and the API answering `{columns, rows}`. */
data class AdminTable(val id: String, val label: String, val url: String)
