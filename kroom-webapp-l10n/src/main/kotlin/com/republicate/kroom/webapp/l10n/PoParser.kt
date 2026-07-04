package com.republicate.kroom.webapp.l10n

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Parser for GNU gettext PO files.
 */
object PoParser {

    fun parse(input: InputStream): Map<String, String> {
        val translations = mutableMapOf<String, String>()
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))

        var currentMsgid: String? = null
        var currentMsgstr: StringBuilder? = null
        var inMsgid = false
        var inMsgstr = false

        fun flushEntry() {
            val msgid = currentMsgid
            val msgstr = currentMsgstr?.toString()
            // Skip the empty-msgid header entry; keep only real, translated pairs.
            if (!msgid.isNullOrEmpty() && !msgstr.isNullOrEmpty()) {
                translations[msgid] = msgstr
            }
            currentMsgid = null
            currentMsgstr = null
            inMsgid = false
            inMsgstr = false
        }

        reader.forEachLine { line ->
            val trimmed = line.trim()

            when {
                trimmed.isEmpty() || trimmed.startsWith("#") -> {
                    flushEntry()
                }
                trimmed.startsWith("msgid ") -> {
                    flushEntry()
                    inMsgid = true
                    inMsgstr = false
                    currentMsgid = extractQuoted(trimmed.substring(6))
                }
                trimmed.startsWith("msgstr ") -> {
                    inMsgid = false
                    inMsgstr = true
                    currentMsgstr = StringBuilder(extractQuoted(trimmed.substring(7)))
                }
                trimmed.startsWith("\"") && trimmed.endsWith("\"") -> {
                    val content = extractQuoted(trimmed)
                    when {
                        inMsgid -> currentMsgid = (currentMsgid ?: "") + content
                        inMsgstr -> currentMsgstr?.append(content)
                    }
                }
            }
        }
        flushEntry()

        return translations
    }

    private fun extractQuoted(s: String): String {
        val trimmed = s.trim()
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length >= 2) {
            return unescape(trimmed.substring(1, trimmed.length - 1))
        }
        return trimmed
    }

    // Single pass, left-to-right: the correct inverse of PoWriter.escape.
    // Sequential replace() would mis-decode a literal backslash before n/t/" .
    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    else -> { sb.append(c); i++ }
                }
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }
}
