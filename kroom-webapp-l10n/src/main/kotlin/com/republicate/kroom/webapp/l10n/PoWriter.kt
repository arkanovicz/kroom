package com.republicate.kroom.webapp.l10n

/**
 * Writer for GNU gettext PO files — the exact inverse of [PoParser].
 *
 * Emits one entry per pair, sorted by msgid for stable, diffable output, and
 * writes every pair faithfully including empty msgstr (untranslated keys stay
 * visible to translators; [PoParser] drops empty ones on read, so it round-trips).
 */
object PoWriter {

    fun write(translations: Map<String, String>): String {
        val sb = StringBuilder()
        // Minimal header for Poedit; PoParser skips the empty-msgid entry on read.
        sb.append("msgid \"\"\n")
        sb.append("msgstr \"Content-Type: text/plain; charset=UTF-8\\n\"\n\n")
        for ((msgid, msgstr) in translations.toSortedMap()) {
            sb.append("msgid \"").append(escape(msgid)).append("\"\n")
            sb.append("msgstr \"").append(escape(msgstr)).append("\"\n\n")
        }
        return sb.toString()
    }

    // Inverse of PoParser.unescape: backslash first, then quote, newline, tab.
    private fun escape(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
}

/** Convenience: export a source's translations for one language as a PO document. */
fun TranslationSource.writePo(iso: String): String = PoWriter.write(getAllTranslations(iso))
