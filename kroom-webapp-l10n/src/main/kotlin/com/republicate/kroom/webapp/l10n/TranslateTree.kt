package com.republicate.kroom.webapp.l10n

import java.io.File

/**
 * Every template under [templates] (`.html`, `.vm`) translated into `<out>/<lang>/<rel>` for each of [languages],
 * from `<i18n>/<lang>.po`; the source language verbatim, literal `#parse` / `#include` targets prefixed with the
 * language so they resolve within the relocated tree. Answers the `(lang, rel)` pairs written.
 */
fun translateTree(templates: File, i18n: File?, sourceLanguage: String, languages: List<String>, out: File): List<Pair<String, String>> {
    val files = templates.walkTopDown().filter { it.isFile && (it.extension == "html" || it.extension == "vm") }.toList()
    val written = ArrayList<Pair<String, String>>()
    for (lang in languages) {
        val translator = TemplateTranslator(catalog(lang, sourceLanguage, i18n), sourceLanguage)
        for (file in files) {
            val rel = file.relativeTo(templates).invariantSeparatorsPath
            File(out, "$lang/$rel").apply { parentFile.mkdirs(); writeText(translator.translate(file.readText(), lang) { "$lang/$it" }) }
            written += lang to rel
        }
    }
    return written
}

/** [translateTree] for a build that cannot apply a plugin of its own repository: `<templates> <i18n|-> <source> <lang,…> <out>`. */
fun main(args: Array<String>) {
    require(args.size == 5) { "usage: <templates> <i18n|-> <source language> <lang,…> <out>" }
    val out = File(args[4]).apply { deleteRecursively(); mkdirs() }
    translateTree(File(args[0]), args[1].takeIf { it != "-" }?.let(::File), args[2], args[3].split(','), out)
}

/** `<i18nDir>/<lang>.po`; nothing for the source language. */
private fun catalog(lang: String, source: String, i18nDir: File?): TranslationSource {
    val map: Map<String, String> =
        if (lang == source || i18nDir == null) emptyMap()
        else File(i18nDir, "$lang.po").takeIf { it.isFile }?.inputStream()?.use { PoParser.parse(it) } ?: emptyMap()
    return object : TranslationSource {
        override fun getTranslation(en: String, iso: String): String? = if (iso == lang) map[en] else null
        override fun getAllTranslations(iso: String): Map<String, String> = if (iso == lang) map else emptyMap()
        override fun isLoaded(iso: String): Boolean = true
    }
}
