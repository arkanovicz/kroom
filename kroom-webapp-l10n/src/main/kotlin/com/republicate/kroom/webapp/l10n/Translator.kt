package com.republicate.kroom.webapp.l10n

import org.apache.velocity.Template
import org.apache.velocity.runtime.parser.node.ASTText
import org.apache.velocity.runtime.parser.node.SimpleNode
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Translator for Velocity template localization.
 *
 * Translates text content in Velocity templates and plain strings.
 * Uses a pluggable TranslationSource for lookups (PO files, database, etc.).
 *
 * This is the *runtime* path: it walks a parsed [Template] AST and rewrites its text nodes in place
 * (translate-before-merge). The build-time, source-to-source counterpart is [TemplateTranslator];
 * both share the HTML text logic in [HtmlFragmentTranslator].
 */
class Translator(
    private val iso: String,
    private val config: L10nConfig,
    private val currentSource: String = "velocity"  // Source identifier for missing tracking
) {

    companion object {
        private val logger = LoggerFactory.getLogger(Translator::class.java)

        // Translation cache per (uri, lang)
        private val translationsCache = ConcurrentHashMap<Pair<String, String>, Template>()

        // Current translator for thread-local access
        val current: ThreadLocal<Translator?> = ThreadLocal()

        fun resetCache() {
            translationsCache.clear()
        }
    }

    private val source: TranslationSource
        get() = config.translationSource

    // Per-template HTML fragment translator (carries <script>/<style> ignore state across nodes).
    private val fragments = HtmlFragmentTranslator { translate(it) }

    init {
        current.set(this)
    }

    /**
     * Translate a single string.
     */
    fun translate(enText: String): String {
        if (iso == config.sourceLanguage) return enText
        val translated = source.getTranslation(enText, iso)
        // Use translation only if non-empty; empty/blank means "not yet translated"
        if (!translated.isNullOrEmpty()) return translated
        // Report missing only if not in DB at all (null vs empty distinction)
        if (translated == null) source.onMissing(enText, iso, currentSource)
        return enText
    }

    /**
     * Translate a Velocity template (caches translated templates).
     */
    fun translate(uri: String, template: Template): Template {
        if (iso == config.sourceLanguage) return template
        val key = uri to iso
        var translated = translationsCache[key]
        if (translated != null && translated.lastModified < template.lastModified) {
            translationsCache.remove(key)
            translated = null
        }
        if (translated == null) {
            synchronized(translationsCache) {
                translated = translationsCache[key]
                if (translated == null) {
                    translated = template.clone() as Template
                    val data = translated.data as SimpleNode
                    translateNode(data)
                    translationsCache[key] = translated
                }
            }
        }
        return translated!!
    }

    private fun translateNode(node: SimpleNode) {
        if (node is ASTText) {
            node.setCtext(fragments.translate(node.getCtext()))
        }
        for (i in 0..<node.jjtGetNumChildren()) {
            translateNode(node.jjtGetChild(i) as SimpleNode)
        }
    }
}
