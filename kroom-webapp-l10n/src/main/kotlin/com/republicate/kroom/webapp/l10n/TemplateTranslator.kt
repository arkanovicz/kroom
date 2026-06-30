package com.republicate.kroom.webapp.l10n

import org.apache.velocity.engine.ASTBlock
import org.apache.velocity.engine.ASTBlockMacroCall
import org.apache.velocity.engine.ASTForeach
import org.apache.velocity.engine.ASTIf
import org.apache.velocity.engine.ASTMacro
import org.apache.velocity.engine.ASTText
import org.apache.velocity.engine.Node
import org.apache.velocity.engine.parse
import org.apache.velocity.engine.parseDiagnostics

/**
 * Build-time, source-to-source template translator.
 *
 * Parses a Velocity template with the 3.0 common front-end, then splices a translated variant of
 * each literal text node back into the original source by its [ASTText.range] — leaving every
 * directive, reference and byte of structure untouched. Reuses [HtmlFragmentTranslator] for the
 * HTML text logic, so `title`/`alt`/`aria-label` and `<script>`/`<style>` handling match the
 * runtime [Translator]. The product is a translated `.vm` that the runtime engine renders today and
 * that `vtlFile` compiles tomorrow — no runtime translation either way.
 */
class TemplateTranslator(
    private val source: TranslationSource,
    private val sourceLanguage: String = "en",
    private val missingSource: String = "velocity"
) {

    /** Translate [templateSource] into [iso]; returns the source unchanged for the source language. */
    fun translate(templateSource: String, iso: String): String {
        if (iso == sourceLanguage) return templateSource
        val diagnostics = parseDiagnostics(templateSource)
        require(diagnostics.isEmpty()) { "Velocity parse error(s): ${diagnostics.joinToString("; ")}" }
        val ast = parse(templateSource)

        // One fragment translator per template: carries <script>/<style> ignore state across nodes,
        // which are visited in document order.
        val fragments = HtmlFragmentTranslator { lookup(it, iso) }
        val edits = ArrayList<Pair<IntRange, String>>()
        collectText(ast) { node ->
            val range = node.range ?: return@collectText
            edits.add(range to fragments.translate(node.value))
        }
        edits.sortBy { it.first.first }

        val out = StringBuilder(templateSource.length)
        var pos = 0
        for ((range, text) in edits) {
            val start = range.first
            val end = range.last + 1
            if (start < pos) continue            // defensive: ignore any overlapping node
            out.append(templateSource, pos, start)  // verbatim span (directives, refs, comments)
            out.append(text)                        // translated text node
            pos = end
        }
        if (pos < templateSource.length) out.append(templateSource, pos, templateSource.length)
        return out.toString()
    }

    private fun lookup(en: String, iso: String): String {
        val translated = source.getTranslation(en, iso)
        if (!translated.isNullOrEmpty()) return translated
        if (translated == null) source.onMissing(en, iso, missingSource)
        return en
    }

    // Visit literal text nodes in document order. Block-bearing nodes recurse; everything else
    // (references, #set, comments, #parse/#include, macro calls) holds no translatable text.
    private fun collectText(node: Node, out: (ASTText) -> Unit) {
        when (node) {
            is ASTText -> out(node)
            is ASTBlock -> node.items.forEach { collectText(it, out) }
            is ASTIf -> {
                node.branches.forEach { collectText(it.body, out) }
                node.orElse?.let { collectText(it, out) }
            }
            is ASTForeach -> {
                collectText(node.body, out)
                node.orElse?.let { collectText(it, out) }
            }
            is ASTMacro -> collectText(node.body, out)
            is ASTBlockMacroCall -> collectText(node.body, out)
            else -> {}
        }
    }
}
