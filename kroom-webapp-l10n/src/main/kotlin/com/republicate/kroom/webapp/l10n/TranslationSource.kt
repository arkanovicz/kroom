package com.republicate.kroom.webapp.l10n

/**
 * Interface for translation sources.
 * Implementations can load translations from PO files, databases, etc.
 */
interface TranslationSource {
    /**
     * Get translation for the given English text in the specified language.
     * @param en English source text
     * @param iso Target language ISO code
     * @return Translated text, or null if not found
     */
    fun getTranslation(en: String, iso: String): String?

    /**
     * Get all translations for a language.
     * @param iso Target language ISO code
     * @return Map of English text to translated text
     */
    fun getAllTranslations(iso: String): Map<String, String>

    /**
     * Check if translations are loaded for a language.
     */
    fun isLoaded(iso: String): Boolean

    /**
     * Called when a translation is missing.
     * Implementations may log, auto-insert, etc.
     * @param en English source text
     * @param iso Target language ISO code
     * @param source Origin of the text (velocity, js, kotlin)
     */
    fun onMissing(en: String, iso: String, source: String? = null) {}

    /**
     * Clear any cached translations.
     */
    fun resetCache() {}
}
