package com.republicate.kroom.webapp.l10n

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Parser for pipe-separated `key|value` translation files (inverse of [PsvWriter]).
 *
 * Each line holds one entry; the separator is the first *unescaped* pipe. Because [PsvWriter]
 * escapes newlines, every entry is exactly one physical line.
 */
object PsvParser {

    fun parse(input: InputStream): Map<String, String> {
        val translations = mutableMapOf<String, String>()
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).forEachLine { line ->
            if (line.isEmpty()) return@forEachLine
            val sep = findSeparator(line) ?: return@forEachLine
            val key = unescape(line.substring(0, sep))
            val value = unescape(line.substring(sep + 1))
            // Keep only real, translated pairs; empty values stay in the file but not the map.
            if (key.isNotEmpty() && value.isNotEmpty()) translations[key] = value
        }
        return translations
    }

    // Index of the first unescaped '|' — data pipes are escaped, so it's the field separator.
    private fun findSeparator(line: String): Int? {
        var i = 0
        while (i < line.length) {
            when (line[i]) {
                '\\' -> i += 2  // skip the escaped char
                '|' -> return i
                else -> i++
            }
        }
        return null
    }

    // Single pass, left-to-right: the correct inverse of PsvWriter.escape.
    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '|' -> { sb.append('|'); i += 2 }
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
