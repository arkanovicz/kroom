package com.republicate.kroom.webapp.authoring

import org.apache.velocity.engine.ResourceLoader
import org.apache.velocity.engine.ResourceNotFoundException
import java.security.MessageDigest

/**
 * A content tree that is both **read by the renderer and written by the editor** — hence a velocity 3.0
 * [ResourceLoader] first (the `%` engine's loader, handed over as `markdown.loader`): the very bytes a visitor's page renders are the ones a submit wrote, with no
 * second path and no cache to reconcile.
 *
 * A stored file is a header region followed by the body. The header carries the block's meta as
 * `%%@ key value` lines (velocity reads them as meta, renders nothing); the editor is shown the body
 * alone. [write] rewrites the keys it is given and leaves every other header line untouched, so meta an
 * application keeps there survives an edit.
 */
abstract class ResourceStore(private val sigil: String = "%%@") : ResourceLoader {

    /** The block at [path], or null when nothing has been written there yet. */
    abstract fun read(path: String): Block?

    /** Write [body], updating the header with [meta]. Answers the block as stored. */
    abstract fun write(path: String, body: String, meta: Map<String, String>): Block

    /** Every path under [prefix] — what boot-time routing and the journal browse. */
    abstract fun list(prefix: String = ""): List<String>

    // --- the source seen by velocity: header + body, exactly as stored ---------------------------

    override fun load(name: String): String =
        source(read(name.trimStart('/')) ?: throw ResourceNotFoundException("no such content: $name"))

    override fun exists(name: String): Boolean = read(name.trimStart('/')) != null

    override fun lastModified(name: String): Long? = read(name.trimStart('/'))?.updated?.takeIf { it > 0 }

    // --- header <-> body, the one place the file's shape is known --------------------------------

    protected fun parse(source: String, path: String): Block {
        val header = LinkedHashMap<String, String>()
        val rest = ArrayList<String>()
        val lines = source.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith(sigil) -> {
                    val content = line.removePrefix(sigil).trim()
                    val key = content.substringBefore(' ').trim()
                    if (key.isNotEmpty()) header[key] = content.substringAfter(' ', "").trim()
                }
                line.isBlank() -> {}
                else -> break
            }
            i++
        }
        rest.addAll(lines.subList(i, lines.size))
        return Block(path, rest.joinToString("\n").trim('\n'), header)
    }

    /** The stored form: the header region, a blank line, the body. */
    protected fun source(block: Block): String {
        val header = block.meta.entries.joinToString("\n") { (k, v) -> "$sigil $k $v" }
        return if (header.isEmpty()) block.body else "$header\n\n${block.body}"
    }

    /** [meta] merged over what the header already holds — an application's own keys are not lost. */
    protected fun merge(path: String, meta: Map<String, String>): Map<String, String> =
        LinkedHashMap(read(path)?.meta.orEmpty()).apply { putAll(meta) }
}

/**
 * A block as stored: the [body] an author edits, the [meta] its header carries, and [rev], the identity of
 * this exact body — a content hash, so an edit made outside the editor (a `git pull`, a deploy) is seen as
 * a conflict just like a concurrent submit, with nothing to keep in sync.
 */
data class Block(val path: String, val body: String, val meta: Map<String, String> = emptyMap()) {
    val rev: String = hash(body)
    val author: String? get() = meta["author"]
    val updated: Long get() = meta["updated"]?.toLongOrNull() ?: 0L

    private companion object {
        fun hash(body: String): String =
            MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
                .take(8).joinToString("") { "%02x".format(it) }
    }
}

/**
 * A store that keeps its past. The application decides how far that goes: a git-backed store answers its
 * log, a plain file store implements nothing of this and the history routes simply do not mount.
 *
 * There is no `revert`: reverting is reading an old body and writing it as a new revision through the
 * ordinary write path, which keeps the journal honest — an undo is an edit like any other.
 */
interface Versioned {
    /** Revisions of [path], or the site-wide journal when it is null, newest first. */
    fun log(path: String? = null, limit: Int = 50): List<Revision>

    /** The block as it was at [rev]. */
    fun read(path: String, rev: String): Block?
}

data class Revision(val rev: String, val path: String, val author: String?, val time: Long, val message: String? = null)
