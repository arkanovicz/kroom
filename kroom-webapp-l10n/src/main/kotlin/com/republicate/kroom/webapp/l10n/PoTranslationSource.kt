package com.republicate.kroom.webapp.l10n

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Translation source backed by PO files (gettext format).
 */
class PoTranslationSource(
    private val i18nPath: String = "/i18n",
    private val logMissing: Boolean = false
) : TranslationSource {

    companion object {
        private val logger = LoggerFactory.getLogger(PoTranslationSource::class.java)
    }

    // Translation bundles loaded from .po files
    private val bundles = ConcurrentHashMap<String, Map<String, String>>()

    /**
     * Load translations for a language from a PO file.
     */
    fun loadBundle(lang: String, resourcePath: String = "$i18nPath/$lang.po") {
        val resource = PoTranslationSource::class.java.getResourceAsStream(resourcePath)
        if (resource != null) {
            bundles[lang] = PoParser.parse(resource)
            logger.info("Loaded ${bundles[lang]?.size ?: 0} translations for $lang from $resourcePath")
        } else {
            logger.warn("No translation file found at $resourcePath for $lang")
            bundles[lang] = emptyMap()
        }
    }

    override fun getTranslation(en: String, iso: String): String? {
        return bundles[iso]?.get(en)
    }

    override fun getAllTranslations(iso: String): Map<String, String> {
        return bundles[iso] ?: emptyMap()
    }

    override fun isLoaded(iso: String): Boolean {
        return bundles.containsKey(iso)
    }

    override fun onMissing(en: String, iso: String, source: String?) {
        if (logMissing) {
            logger.debug("No translation found for '{}' in {}", en, iso)
        }
    }

    override fun resetCache() {
        // PO files are static, but we can reload if needed
        bundles.clear()
    }
}
