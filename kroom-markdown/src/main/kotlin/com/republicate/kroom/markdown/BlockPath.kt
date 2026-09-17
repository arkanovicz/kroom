package com.republicate.kroom.markdown

import com.republicate.kroom.PathTemplate

/**
 * Where `#markdown("description")` reads from. A block sits beside the template including it, so its path
 * is relative to that template, and the placeholders the page was routed by (`pages/club/_code_/club.html`)
 * are the ones the block inherits — expanded from the very values the router bound, never guessed.
 *
 * A bare name takes the `.md` extension; anything with a `/` or an extension is a relative path.
 */
internal fun blockPath(argument: String, includingTemplate: String?, values: (String) -> Any?): String {
    val relative = if ('/' in argument || '.' in argument.substringAfterLast('/')) argument else "$argument.md"
    val base = includingTemplate?.substringBeforeLast('/', "").orEmpty()
    val joined = if (base.isEmpty()) relative else "$base/$relative"
    return PathTemplate(normalize(joined)).expand(values)
}

private fun normalize(path: String): String {
    val out = ArrayList<String>()
    for (segment in path.split('/')) when (segment) {
        "", "." -> {}
        ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1) else throw IllegalArgumentException("path escapes the tree: '$path'")
        else -> out.add(segment)
    }
    return out.joinToString("/")
}
