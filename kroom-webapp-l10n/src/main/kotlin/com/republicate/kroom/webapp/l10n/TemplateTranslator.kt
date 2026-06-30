package com.republicate.kroom.webapp.l10n

import org.apache.velocity.engine.ASTBlock
import org.apache.velocity.engine.ASTBlockMacroCall
import org.apache.velocity.engine.ASTForeach
import org.apache.velocity.engine.ASTIf
import org.apache.velocity.engine.ASTInclude
import org.apache.velocity.engine.ASTMacro
import org.apache.velocity.engine.ASTParse
import org.apache.velocity.engine.ASTText
import org.apache.velocity.engine.Expr
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

    /**
     * Translate [templateSource] into [iso]. Text nodes are translated for non-source languages;
     * [includePathRewrite], if given, rewrites the *literal* target of each `#parse`/`#include`
     * (e.g. to prepend a language prefix) for *every* language — the relocated trees need it even for
     * the source language. A dynamic (`$var`) target is left as-is; the velocity compiler rejects it
     * anyway. Returns the source unchanged only when neither applies.
     */
    fun translate(
        templateSource: String,
        iso: String,
        includePathRewrite: ((String) -> String)? = null
    ): String {
        val translateText = iso != sourceLanguage
        if (!translateText && includePathRewrite == null) return templateSource
        val diagnostics = parseDiagnostics(templateSource)
        require(diagnostics.isEmpty()) { "Velocity parse error(s): ${diagnostics.joinToString("; ")}" }
        val ast = parse(templateSource)

        // One fragment translator per template: carries <script>/<style> ignore state across nodes,
        // which are visited in document order.
        val fragments = if (translateText) HtmlFragmentTranslator { lookup(it, iso) } else null
        val edits = ArrayList<Pair<IntRange, String>>()
        collect(ast, fragments, includePathRewrite, edits)
        edits.sortBy { it.first.first }

        val out = StringBuilder(templateSource.length)
        var pos = 0
        for ((range, text) in edits) {
            val start = range.first
            val end = range.last + 1
            if (start < pos) continue            // defensive: ignore any overlapping node
            out.append(templateSource, pos, start)  // verbatim span (directives, refs, comments)
            out.append(text)                        // translated text / rewritten include target
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

    // Visit nodes in document order. Text nodes translate; #parse/#include literal targets rewrite;
    // block-bearing nodes recurse; everything else (references, #set, comments, macro calls) is verbatim.
    private fun collect(
        node: Node,
        fragments: HtmlFragmentTranslator?,
        includePathRewrite: ((String) -> String)?,
        edits: MutableList<Pair<IntRange, String>>
    ) {
        when (node) {
            is ASTText -> if (fragments != null) node.range?.let { edits += it to fragments.translate(node.value) }
            is ASTParse -> includePathRewrite?.let { literalTargetEdit(node.target, it)?.let(edits::add) }
            is ASTInclude -> includePathRewrite?.let { literalTargetEdit(node.target, it)?.let(edits::add) }
            is ASTBlock -> node.items.forEach { collect(it, fragments, includePathRewrite, edits) }
            is ASTIf -> {
                node.branches.forEach { collect(it.body, fragments, includePathRewrite, edits) }
                node.orElse?.let { collect(it, fragments, includePathRewrite, edits) }
            }
            is ASTForeach -> {
                collect(node.body, fragments, includePathRewrite, edits)
                node.orElse?.let { collect(it, fragments, includePathRewrite, edits) }
            }
            is ASTMacro -> collect(node.body, fragments, includePathRewrite, edits)
            is ASTBlockMacroCall -> collect(node.body, fragments, includePathRewrite, edits)
            else -> {}
        }
    }

    // A literal `"path"` target → an edit replacing it with `"rewrite(path)"`; null if dynamic.
    private fun literalTargetEdit(target: Expr?, rewrite: (String) -> String): Pair<IntRange, String>? {
        val range = target?.range ?: return null
        val src = target.source
        if (src.length < 2 || src.first() != '"' || src.last() != '"') return null
        return range to "\"${rewrite(src.substring(1, src.length - 1))}\""
    }
}
