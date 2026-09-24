package com.republicate.kroom.webapp.authoring

import com.republicate.kroom.webapp.assets.KroomAssets

/**
 * The editor's own files. They live under this jar's `static/`, which `installCore`'s `staticRoutes`
 * already serves from any jar on the classpath — so authoring mounts no route of its own.
 *
 * The tags go in the application's layout, **after** the house stack (domhelper.js, api.js): authoring.js
 * builds on it and does not reload it. From a velocity layout, `$authoring.assets.tags()`.
 */
object AuthoringAssets {

    const val VERSION = KroomAssets.VERSION

    @JvmOverloads fun styleTags(prefix: String = "") =
        """<link rel="stylesheet" href="${prefix.trimEnd('/')}/css/authoring.css?v=$VERSION">"""

    @JvmOverloads fun scriptTags(prefix: String = "") = listOf(
        """<script src="${prefix.trimEnd('/')}/lib/diff-match-patch/diff_match_patch.js?v=$VERSION"></script>""",
        """<script src="${prefix.trimEnd('/')}/js/authoring.js?v=$VERSION"></script>"""
    ).joinToString("\n")

    @JvmOverloads fun tags(prefix: String = "") = styleTags(prefix) + "\n" + scriptTags(prefix)
}
