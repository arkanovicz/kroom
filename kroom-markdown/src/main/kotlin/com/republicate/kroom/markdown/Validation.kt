package com.republicate.kroom.markdown

import org.apache.velocity.engine.ASTForeach
import org.apache.velocity.engine.ASTIf
import org.apache.velocity.engine.ASTReference
import org.apache.velocity.engine.CondAnd
import org.apache.velocity.engine.CondLeaf
import org.apache.velocity.engine.CondNot
import org.apache.velocity.engine.CondOr
import org.apache.velocity.engine.Cond
import org.apache.velocity.engine.Config
import org.apache.velocity.engine.Expr
import org.apache.velocity.engine.Node
import org.apache.velocity.engine.ParseErrorException
import org.apache.velocity.engine.analyze
import org.apache.velocity.engine.analyzeFragments
import org.apache.velocity.engine.parse

/** A problem an author can act on; [line]/[column] are 1-based, null when the engine gives no position. */
data class Problem(val message: String, val line: Int? = null, val column: Int? = null)

/**
 * Save-time check of a `%` block against its includer: [declared] are the context roots the including
 * template provides (its header names, typically). Reports a parse error, or each root the block reads
 * that is neither declared nor bound by the block itself — the error a visitor would otherwise meet, at
 * render, under strict mode.
 */
fun validate(source: String, declared: Set<String>, name: String = "markdown"): List<Problem> {
    val block = try {
        parse(source, Config(lexerSource = MarkdownVtl), name)
    } catch (e: ParseErrorException) {
        return listOf(Problem(e.message.orEmpty(), e.line, e.column))
    }
    // a block's own defaults are bindings; its bare needs still fall to the includer
    val bound = block.header.filter { it.default != null }.mapTo(HashSet()) { it.name }
    val free = analyzeFragments(block).depsOf(block)?.reads.orEmpty()
    val missing = free - declared - bound
    if (missing.isEmpty()) return emptyList()
    val triggers = analyze(block).nodes
    return missing.map { root ->
        val at = triggers.firstNotNullOfOrNull { (node, roots) -> if (root in roots) positionOf(node) else null }
        Problem("undeclared reference \$$root", at?.first, at?.second)
    }
}

private fun positionOf(node: Node): Pair<Int, Int>? = when (node) {
    is ASTReference -> node.expr.position()
    is ASTForeach -> node.sequence.position()
    is ASTIf -> node.branches.firstNotNullOfOrNull { it.condition.firstLeaf().position() }
    else -> null
}

private fun Expr.position(): Pair<Int, Int>? = line?.let { it to (column ?: 0) }

private fun Cond.firstLeaf(): Expr = when (this) {
    is CondLeaf -> expr
    is CondNot -> operand.firstLeaf()
    is CondAnd -> left.firstLeaf()
    is CondOr -> left.firstLeaf()
}
