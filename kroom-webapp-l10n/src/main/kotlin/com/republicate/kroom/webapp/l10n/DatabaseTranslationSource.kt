package com.republicate.kroom.webapp.l10n

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Translation source backed by a database table.
 *
 * This implementation uses callback functions to avoid coupling to a specific ORM.
 * The application provides lambdas for database operations.
 *
 * @param fetchOne Function to fetch one translation: (en, iso) -> translated?
 * @param fetchAll Function to fetch all translations for a language: (iso) -> Map<en, translated>
 * @param insertMissing Function to insert a missing translation: (en, iso, source) -> Unit
 * @param autoInsert If true, missing translations are auto-inserted
 */
class DatabaseTranslationSource(
    private val fetchOne: (en: String, iso: String) -> String?,
    private val fetchAll: (iso: String) -> Map<String, String>,
    private val insertMissing: ((en: String, iso: String, source: String?) -> Unit)? = null,
    private val autoInsert: Boolean = true
) : TranslationSource {

    companion object {
        private val logger = LoggerFactory.getLogger(DatabaseTranslationSource::class.java)
    }

    // Cache: (iso, en) -> translated (null means "looked up, not found")
    private val cache = ConcurrentHashMap<Pair<String, String>, String?>()
    private val cacheChecked = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    // Track which languages have been fully loaded
    private val loadedLanguages = ConcurrentHashMap.newKeySet<String>()

    override fun getTranslation(en: String, iso: String): String? {
        val key = iso to en
        // Check cache first
        if (cacheChecked.contains(key)) {
            return cache[key]
        }

        // Query database
        return try {
            val translated = fetchOne(en, iso)
            cache[key] = translated
            cacheChecked.add(key)
            translated
        } catch (e: Exception) {
            logger.error("Failed to fetch translation for '{}' in {}", en, iso, e)
            null
        }
    }

    override fun getAllTranslations(iso: String): Map<String, String> {
        // Return from cache if already loaded
        if (loadedLanguages.contains(iso)) {
            return cache.filterKeys { it.first == iso }
                .mapNotNull { (key, value) -> value?.let { key.second to it } }
                .toMap()
        }

        return try {
            val result = fetchAll(iso)
            // Populate cache
            result.forEach { (en, translated) ->
                val key = iso to en
                cache[key] = translated
                cacheChecked.add(key)
            }
            loadedLanguages.add(iso)
            result
        } catch (e: Exception) {
            logger.error("Failed to load translations for {}", iso, e)
            emptyMap()
        }
    }

    override fun isLoaded(iso: String): Boolean {
        return loadedLanguages.contains(iso)
    }

    override fun onMissing(en: String, iso: String, source: String?) {
        if (!autoInsert || insertMissing == null) {
            logger.debug("Missing translation for '{}' in {}", en, iso)
            return
        }

        val key = iso to en
        // Skip if already checked (avoid repeated inserts)
        if (cacheChecked.contains(key)) {
            return
        }

        // Auto-insert missing translation
        try {
            logger.info("Auto-inserting missing translation: '{}' for {} (source={})", en, iso, source)
            insertMissing.invoke(en, iso, source)
            cache[key] = null
            cacheChecked.add(key)
        } catch (e: Exception) {
            // Ignore duplicate key errors (race condition)
            val msg = e.message ?: ""
            if (!msg.contains("duplicate key", ignoreCase = true) &&
                !msg.contains("unique constraint", ignoreCase = true)) {
                logger.error("Failed to insert missing translation: '{}' for {}", en, iso, e)
            }
        }
    }

    override fun resetCache() {
        cache.clear()
        cacheChecked.clear()
        loadedLanguages.clear()
    }
}
