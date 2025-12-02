package com.republicate.kroom.webapp.l10n

import org.apache.velocity.Template
import org.apache.velocity.runtime.parser.node.ASTText
import org.apache.velocity.runtime.parser.node.SimpleNode
import org.slf4j.LoggerFactory
import java.io.PrintWriter
import java.io.StringWriter
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Translator for gettext-based localization.
 *
 * Translates text content in Velocity templates and plain strings.
 */
class Translator(private val iso: String, private val config: L10nConfig) {

    companion object {
        private val logger = LoggerFactory.getLogger(Translator::class.java)

        // ASTText.ctext is protected, need reflection
        private val textAccessor = ASTText::class.java.getDeclaredField("ctext").also { it.isAccessible = true }

        // Translation bundles (loaded from .po files)
        private val bundles = ConcurrentHashMap<String, Map<String, String>>()

        // Translation cache per (uri, lang)
        private val translationsCache = ConcurrentHashMap<Pair<String, String>, Template>()

        // Current translator for thread-local access
        val current: ThreadLocal<Translator?> = ThreadLocal()

        private val sep = "(?:[ \\r\\n\\t\u00A0/–-]|&nbsp;|&dash;)*"
        private val textExtractor = Regex(
            """<[^>]+\splaceholder="(?<placeholder>[^"]*)"[^>]*>|(?<=>)$sep(?<text>[^<>]+?)$sep(?=<|${'$'})|(?<=>|^)$sep(?<text2>[^<>]+?)$sep(?=<)""",
            RegexOption.DOT_MATCHES_ALL
        )
        private val ignoreExp = Regex("(<script)|(</script>)|(<style)|(</style)")

        fun loadBundle(lang: String, resourcePath: String) {
            val resource = Translator::class.java.getResourceAsStream(resourcePath)
            if (resource != null) {
                bundles[lang] = PoParser.parse(resource)
                logger.info("Loaded ${bundles[lang]?.size ?: 0} translations for $lang from $resourcePath")
            } else {
                logger.warn("No translation file found at $resourcePath for $lang")
                bundles[lang] = emptyMap()
            }
        }

        fun isLoaded(lang: String) = bundles.containsKey(lang)

        fun resetCache() {
            translationsCache.clear()
        }
    }

    init {
        current.set(this)
    }

    private var ASTText.text
        get() = textAccessor.get(this) as String
        set(value) { textAccessor.set(this, value) }

    /**
     * Translate a single string.
     */
    fun translate(enText: String): String {
        if (iso == config.sourceLanguage) return enText
        val bundle = bundles[iso] ?: return enText
        return bundle[enText] ?: enText.also {
            if (config.logMissing) {
                logger.debug("No translation found for '{}' in {}", enText, iso)
            }
        }
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
                    val data = translated!!.data as SimpleNode
                    translateNode(data)
                    translationsCache[key] = translated!!
                }
            }
        }
        return translated!!
    }

    private fun translateNode(node: SimpleNode) {
        if (node is ASTText) {
            node.text = translateFragments(node.text)
        }
        for (i in 0..<node.jjtGetNumChildren()) {
            translateNode(node.jjtGetChild(i) as SimpleNode)
        }
    }

    private var ignoring = false

    private fun MatchResult.firstValidGroup(): Pair<Int, MatchGroup> {
        val groups = this.groups
        var i = 1
        val last = groups.size - 1
        while (i <= last && groups[i]?.range?.first == null) i++
        return i to (groups[i] ?: error("unexpected case"))
    }

    private fun translateFragments(text: String): String {
        val ignoreMap = buildIgnoreMap(text)
        val sw = StringWriter()
        val output = PrintWriter(sw)
        var pos = 0
        while (true) {
            val match = textExtractor.find(text, pos) ?: break
            val start = match.range.first
            val end = match.range.last + 1
            if (start > pos) output.print(text.substring(pos, start))
            val ignore: Boolean = ignoreMap.floorEntry(start)?.value ?: false
            if (ignore) {
                output.print(text.substring(start, end))
            } else {
                val (_, group) = match.firstValidGroup()
                val groupStart = group.range.first
                if (groupStart > start) output.print(text.substring(start, groupStart))
                var token = unescapeHtml(group.value)
                if (containsOnlyIgnorable(token)) {
                    output.print(group.value)
                } else {
                    token = normalize(token)
                    token = translate(token)
                    output.print(escapeHtml(token))
                }
                val groupEnd = group.range.last + 1
                if (groupEnd < end) output.print(text.substring(groupEnd, end))
            }
            pos = end
        }
        if (pos < text.length) output.print(text.substring(pos))
        return sw.toString()
    }

    private fun normalize(str: String) = str.replace("\\s+".toRegex(), " ")

    private fun containsOnlyIgnorable(s: String) = s.all { it in "\r\n\t -;:.\"/<>\u00A00123456789€!" }

    private fun buildIgnoreMap(text: String): NavigableMap<Int, Boolean> {
        val ret: NavigableMap<Int, Boolean> = TreeMap()
        var pos = 0
        var ignore = ignoring
        while (true) {
            val match = ignoreExp.find(text, pos) ?: break
            val start = match.range.first
            val end = match.range.last + 1
            val (groupIndex, group) = match.firstValidGroup()
            val groupStart = group.range.first
            ignore = (groupIndex % 2 != 0)
            if (ret.isEmpty() && start > 0) ret[0] = !ignore
            val groupEnd = group.range.last + 1
            ret[if (ignore) groupStart else groupEnd] = ignore
            pos = end
        }
        if (ret.isEmpty()) ret[0] = ignoring
        else ignoring = ignore
        return ret
    }

    private fun escapeHtml(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun unescapeHtml(s: String) = s
        .replace("&nbsp;", "\u00A0")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
}
