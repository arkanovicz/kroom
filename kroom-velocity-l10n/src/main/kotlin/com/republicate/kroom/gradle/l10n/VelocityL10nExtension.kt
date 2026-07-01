package com.republicate.kroom.gradle.l10n

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Configuration for the `com.republicate.kroom.velocity-l10n` plugin.
 *
 * Lazy properties throughout, so the Kotlin DSL `=` assignment works (Gradle 8.4+):
 * ```
 * velocityL10n {
 *     templates = layout.projectDirectory.dir("src/main/resources/templates")
 *     i18n = layout.projectDirectory.dir("src/main/resources/i18n")
 *     sourceLanguage = "en"
 *     languages = listOf("en", "fr")
 *     appScope = "com.example.AppScope"
 *     sessionScope = "com.example.SessionScope"
 *     requestScope = "com.example.RequestScope"
 * }
 * ```
 * The scope properties are fully-qualified type names (strings): the velocity K2 plugin resolves
 * each `$ref` against their declared members via FIR at compile time, so the types live in the
 * consumer's own source — not here.
 */
abstract class VelocityL10nExtension {
    /** Root of the source Velocity templates (`.html`/`.vm`). */
    abstract val templates: DirectoryProperty

    /** Directory of `<lang>.po` translation bundles. Optional (omit for a single-language app). */
    abstract val i18n: DirectoryProperty

    /** Authoring language; its templates are emitted verbatim (no translation). Default `en`. */
    abstract val sourceLanguage: Property<String>

    /** Languages to emit, including the source language. */
    abstract val languages: ListProperty<String>

    /** FQN of the application-scope type (request > session > app precedence). */
    abstract val appScope: Property<String>

    /** FQN of the session-scope type. */
    abstract val sessionScope: Property<String>

    /** FQN of the request-scope type (highest precedence). */
    abstract val requestScope: Property<String>

    /**
     * Extra `.vtl` macro-library files whose `#macro`s are globally available to every template
     * (in addition to kroom's own `kroom-macros.vtl`, always included). Macros should be
     * language-neutral — put translatable text in templates, not macros. Each is copied verbatim.
     */
    abstract val macroLibraries: ListProperty<String>

    /** Package of the generated dispatcher. Default `com.republicate.kroom.l10n.generated`. */
    abstract val generatedPackage: Property<String>
}
