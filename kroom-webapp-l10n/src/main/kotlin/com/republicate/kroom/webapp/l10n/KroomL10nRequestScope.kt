package com.republicate.kroom.webapp.l10n

/**
 * Request-scope members the l10n plugin contributes to build-time-compiled templates: `$lang`,
 * `$languages`, `$jsTranslations`.
 *
 * Extend it in your app's request-scope type so the `kroom-velocity-l10n` stubs resolve those refs
 * against typed members:
 * ```
 * class MyRequestScope(
 *     override val lang: String,
 *     override val languages: Map<String, String>,
 *     override val jsTranslations: String,
 * ) : KroomL10nRequestScope
 * ```
 */
interface KroomL10nRequestScope {
    val lang: String
    val languages: Map<String, String>
    val jsTranslations: String
}
