package com.republicate.kroom.webapp.authoring

import com.republicate.kroom.webapp.assets.KroomAssets
import com.republicate.kson.Json

/**
 * The editor's own files. They live under this jar's `static/`, which `installCore`'s `staticRoutes`
 * already serves from any jar on the classpath — so authoring mounts no route of its own.
 *
 * The tags go in the application's layout, **after** the house stack (domhelper.js, api.js): authoring.js
 * builds on it and does not reload it. From a velocity layout, `$authoring.assets.tags()`.
 *
 * [language] picks the stock language ([AuthoringConfig.language]); it reaches the page ahead of authoring.js,
 * which reads it at load.
 */
class AuthoringAssets(private val language: String = "auto") {

    companion object {
        const val VERSION = KroomAssets.VERSION
    }

    fun styleTags(prefix: String = "") =
        """<link rel="stylesheet" href="${prefix.trimEnd('/')}/css/authoring.css?v=$VERSION">"""

    /**
     * The language, and [words] a script reads beside its own (the plugins', for the admin bar), for the script
     * whose global is [target] — the editor's, or the admin bar's (`kroomAdmin`).
     */
    fun stringTags(target: String = "kroomAuthoring", words: Map<String, String> = emptyMap()): String {
        if (words.isEmpty() && language == "auto") return ""
        val lang = Json.MutableObject().apply { set("language", language) }.toString()
        // `</` would close the script element early, whatever the JSON says
        val json = Json.MutableObject().apply { words.forEach { (key, value) -> set(key, value) } }.toString()
            .replace("</", "<\\/")
        return "<script>Object.assign((window.$target ??= {}), $lang); Object.assign((window.$target.strings ??= {}), $json);</script>"
    }

    fun scriptTags(prefix: String = "") = listOfNotNull(
        stringTags().takeIf { it.isNotEmpty() },
        """<script src="${prefix.trimEnd('/')}/lib/diff-match-patch/diff_match_patch.js?v=$VERSION"></script>""",
        """<script src="${prefix.trimEnd('/')}/js/authoring.js?v=$VERSION"></script>"""
    ).joinToString("\n")

    fun tags(prefix: String = "") = styleTags(prefix) + "\n" + scriptTags(prefix)
}
