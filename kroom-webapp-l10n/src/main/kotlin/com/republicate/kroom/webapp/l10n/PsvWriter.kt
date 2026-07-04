package com.republicate.kroom.webapp.l10n

/**
 * Writer for pipe-separated `key|value` translation files — the exact inverse of [PsvParser].
 *
 * One entry per line, sorted by key for stable, diffable output. Empty values are written
 * faithfully (untranslated keys stay visible; [PsvParser] drops empty ones on read, so it
 * round-trips). Values are escaped so every line holds exactly one entry regardless of content.
 */
object PsvWriter {

    fun write(translations: Map<String, String>): String {
        val sb = StringBuilder()
        for ((key, value) in translations.toSortedMap()) {
            sb.append(escape(key)).append('|').append(escape(value)).append('\n')
        }
        return sb.toString()
    }

    // Inverse of PsvParser.unescape: backslash first, then pipe, newline, tab.
    private fun escape(s: String): String =
        s.replace("\\", "\\\\")
            .replace("|", "\\|")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
}

/** Convenience: export a source's translations for one language as a PSV document. */
fun TranslationSource.writePsv(iso: String): String = PsvWriter.write(getAllTranslations(iso))
