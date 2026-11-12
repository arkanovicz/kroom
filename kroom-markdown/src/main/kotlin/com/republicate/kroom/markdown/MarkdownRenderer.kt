package com.republicate.kroom.markdown

import org.apache.velocity.engine.Context
import org.apache.velocity.engine.ResourceNotFoundException
import org.apache.velocity.engine.VelocityContext
import org.apache.velocity.engine.VelocityEngine
import org.apache.velocity.engine.jvm.ScriptingCompiler
import org.apache.velocity.engine.runtime.introspection.Acl
import org.apache.velocity.engine.runtime.introspection.Sandbox

/**
 * Renders a `%`-dialect markdown template to HTML: merge first, convert second. The order is the whole
 * point — a block is VTL that *produces* markdown, so `%foreach` may emit list items and `$name` may
 * land inside a heading, and flexmark sees a finished document rather than a template.
 *
 * What comes out is sanitized ([Markdown.html]): a block keeps its html and loses its scripting. What a
 * reference inserts is not the block's text: it is set aside while the block is merged ([Slots]) — a value
 * is shown as typed, wherever it lands; a tool's markup, a `SafeText`, passes as the tool wrote it.
 *
 * Built on velocity 3.0 directly, never on the classic facade: whatever engine the application renders its
 * pages with, blocks run on this one, configured by [MarkdownConfig] alone.
 */
class MarkdownRenderer(val config: MarkdownConfig = MarkdownConfig()) {

    private val compiler = markdownCompiler(config)
    private val engine = markdownEngine(config, compiler)

    /** What blocks may navigate on a type, as this renderer's sandbox decides — for an editor's completion. */
    internal val navigation get() = compiler.navigation

    /** Render the markdown template at [path], as [MarkdownConfig.loader] serves it. */
    fun render(path: String, context: Context): String = try {
        emit { slots -> engine.mergeTemplate(path, scoped(context, slots), slots.out) }
    } catch (_: ResourceNotFoundException) {
        // an unwritten block is a normal state of a live content tree, not a failure
        emit { slots -> engine.evaluate(config.missing, scoped(context, slots).also { it.put("name", blockName(path)) }, slots.out, "missing") }
    }

    /** Whether the loader holds a block at [path]. */
    fun exists(path: String): Boolean = engine.resourceExists(path)

    /** Render markdown the caller already holds — an editor preview, a draft never written to a loader. */
    fun renderSource(source: String, context: Context, name: String = "markdown"): String =
        emit { slots -> engine.evaluate(source, scoped(context, slots), slots.out, name) }

    /** What [merge] writes, converted, sanitized, and what it set aside put back. */
    private fun emit(merge: (Slots) -> Unit): String {
        val slots = Slots().also(merge)
        return slots.restore(Markdown.html(slots.out.toString(), slots::write, slots::holds))
    }
}

/** A block's name, as `missing`, `broken` and the wrapper see it: `pages/club/13Ma/description.md` → `description`. */
internal fun blockName(path: String) = path.substringAfterLast('/').substringBeforeLast('.')

/**
 * The context a caller hands in comes back as it went: reads fall through to it, writes (`%set($x = 1)`)
 * stay in the child and die at the block's boundary. (Which names that context holds is `#markdown`'s
 * business: a block's arguments and the named tools.) The render's [slots] ride along.
 */
private fun scoped(context: Context, slots: Slots): VelocityContext =
    VelocityContext(parent = context).also { it.put(Slots.KEY, slots) }

/**
 * The one place a `%` sub-engine is built. The sandbox's parent loader is the thread-context one, the
 * application's (so ktor's dev reload still sees fresh classes). The lexer is forced last: a markdown
 * block is `%`-VTL by definition, and no configuration may say otherwise.
 */
private fun markdownCompiler(config: MarkdownConfig) = ScriptingCompiler(sandbox = config.acl?.let {
    Sandbox(Acl.parse(it), Thread.currentThread().contextClassLoader ?: MarkdownRenderer::class.java.classLoader)
})

private fun markdownEngine(config: MarkdownConfig, compiler: ScriptingCompiler) = VelocityEngine(
    compiler = compiler,
    // the one loader, the store's: kept as `markdown.resource.loader.blocks.*` says (nothing, by default)
    config = config.engine.copy(lexerSource = MarkdownVtl, loaders = listOf(BLOCKS)),
).apply {
    addResourceLoader(BLOCKS, config.loader)
    referenceInsertionEvents = SetAside
}

/** The name the block engine lists its store under. */
private const val BLOCKS = "blocks"
