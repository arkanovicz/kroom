package com.republicate.kroom.webapp.l10n

import com.republicate.kroom.webapp.velocity.velocity
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import org.apache.velocity.VelocityContext
import java.io.StringWriter

/**
 * Localization plugin for Ktor webapps.
 *
 * Provides:
 * - Language detection from URL prefix (/en/..., /fr/...)
 * - Accept-Language header parsing
 * - PO file translation loading
 * - Velocity template translation
 */

class L10nConfig {
    var sourceLanguage: String = "en"
    var defaultLanguage: String = "en"
    var languages: Map<String, String> = mapOf("en" to "English")
    var i18nPath: String = "/i18n"
    var logMissing: Boolean = false

    fun language(iso: String, name: String) {
        languages = languages + (iso to name)
    }
}

private val L10nConfigKey = AttributeKey<L10nConfig>("L10nConfig")
private val LanguageKey = AttributeKey<String>("Language")

val Application.l10nConfig: L10nConfig
    get() = attributes[L10nConfigKey]

val ApplicationCall.language: String
    get() = attributes.getOrNull(LanguageKey) ?: application.l10nConfig.defaultLanguage

/**
 * Install localization plugin.
 */
fun Application.installL10n(block: L10nConfig.() -> Unit = {}) {
    val config = L10nConfig().apply(block)
    attributes.put(L10nConfigKey, config)

    // Load translation bundles
    config.languages.keys.forEach { lang ->
        if (lang != config.sourceLanguage) {
            Translator.loadBundle(lang, "${config.i18nPath}/$lang.po")
        }
    }

    // Intercept requests for language routing
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()

        // Skip API and static resources
        if (path.startsWith("/api/") || path.startsWith("/css/") ||
            path.startsWith("/js/") || path.startsWith("/img/") ||
            path.startsWith("/lib/") ||
            path == "/health" || path == "/favicon.ico") {
            return@intercept
        }

        // Check for language prefix: /en/... or /fr/...
        val langMatch = Regex("^/([a-z]{2})(/.*)?$").find(path)

        if (langMatch != null) {
            val lang = langMatch.groupValues[1]
            if (lang in config.languages) {
                call.attributes.put(LanguageKey, lang)
            } else {
                // Unknown language, redirect to preferred
                val preferredLang = getPreferredLanguage(call.request.header(HttpHeaders.AcceptLanguage), config)
                val newPath = "/$preferredLang${langMatch.groupValues[2] ?: ""}"
                call.respondRedirect(newPath, permanent = false)
                finish()
            }
        } else {
            // No language prefix, redirect to preferred language
            val preferredLang = getPreferredLanguage(call.request.header(HttpHeaders.AcceptLanguage), config)
            val newPath = "/$preferredLang$path"
            call.respondRedirect(newPath, permanent = false)
            finish()
        }
    }

    routing {
        // API endpoint to get available languages
        get("/api/i18n") {
            val result = com.republicate.kson.Json.MutableObject().apply {
                set("languages", com.republicate.kson.Json.MutableArray().apply {
                    config.languages.forEach { (iso, name) ->
                        add(com.republicate.kson.Json.MutableObject().apply {
                            set("iso", iso)
                            set("name", name)
                        })
                    }
                })
                set("default", config.defaultLanguage)
            }
            call.respondText(result.toString(), ContentType.Application.Json)
        }
    }
}

private fun getPreferredLanguage(acceptLanguage: String?, config: L10nConfig): String {
    if (acceptLanguage.isNullOrBlank()) return config.defaultLanguage

    val langs = acceptLanguage.split(",")
        .map { it.trim() }
        .map { part ->
            val (lang, q) = if (";q=" in part) {
                val split = part.split(";q=")
                split[0] to (split.getOrNull(1)?.toDoubleOrNull() ?: 1.0)
            } else {
                part to 1.0
            }
            lang.substringBefore("-").lowercase() to q
        }
        .sortedByDescending { it.second }

    return langs.firstOrNull { it.first in config.languages }?.first ?: config.defaultLanguage
}

/**
 * Respond with translated Velocity template.
 */
suspend fun ApplicationCall.respondVelocityTranslated(
    templatePath: String,
    model: Map<String, Any?> = emptyMap(),
    contentType: ContentType = ContentType.Text.Html
) {
    val template = application.velocity.engine.getTemplate(templatePath)
    val translator = Translator(language, application.l10nConfig)
    val translatedTemplate = translator.translate(templatePath, template)

    val context = VelocityContext()
    // Add language info to context
    context.put("lang", language)
    context.put("languages", application.l10nConfig.languages)
    // Add custom model
    model.forEach { (key, value) -> context.put(key, value) }

    val writer = StringWriter()
    translatedTemplate.merge(context, writer)

    respondText(writer.toString(), contentType)
}

/**
 * Extension for RoutingContext.
 */
suspend fun RoutingContext.respondVelocityTranslated(
    templatePath: String,
    model: Map<String, Any?> = emptyMap(),
    contentType: ContentType = ContentType.Text.Html
) {
    call.respondVelocityTranslated(templatePath, model, contentType)
}
