package com.republicate.kroom.webapp.velocity

import com.republicate.kroom.webapp.core.WebResourceVersionCache

/**
 * Application-scope members kroom core contributes to build-time-compiled templates (`$versions`).
 *
 * Extend it in your app's app-scope type so the `kroom-velocity-l10n` stubs resolve `$versions`
 * against a typed member:
 * ```
 * class MyAppScope(override val versions: WebResourceVersionCache, /* … */) : KroomAppScope
 * ```
 */
interface KroomAppScope {
    val versions: WebResourceVersionCache
}
