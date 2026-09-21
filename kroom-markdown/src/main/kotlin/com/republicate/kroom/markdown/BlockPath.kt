package com.republicate.kroom.markdown

import com.republicate.kroom.PathTemplate

/**
 * Where `#markdown("description")` reads from. A block belongs to the PAGE including it, not to the folder
 * that page sits in: `pages/club/_code_.html` and `pages/club/_code_/index.html` both give their blocks
 * `pages/club/_code_/`, so two sibling pages never silently share one. The placeholders the page was routed
 * by locate its blocks too, expanded from the page's values — the very ones the router bound, never guessed.
 *
 * A bare name takes the `.md` extension; a `/` or an extension makes it a relative path; a leading `/`
 * addresses the content root, for a block several pages share on purpose.
 */
internal fun blockPath(argument: String, includingTemplate: String?, values: (String) -> Any?): String {
    val relative = if ('/' in argument || '.' in argument.substringAfterLast('/')) argument else "$argument.md"
    val joined = if (relative.startsWith("/")) relative else "${pageFolder(includingTemplate)}/$relative"
    return PathTemplate(normalize(joined)).expand(values)
}

/** The page's own folder: its path without the extension, an `index` page standing for its directory. */
private fun pageFolder(includingTemplate: String?): String =
    includingTemplate.orEmpty().substringBeforeLast('.').removeSuffix("/index")

private fun normalize(path: String): String {
    val out = ArrayList<String>()
    for (segment in path.split('/')) when (segment) {
        "", "." -> {}
        ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1) else throw IllegalArgumentException("path escapes the tree: '$path'")
        else -> out.add(segment)
    }
    return out.joinToString("/")
}
