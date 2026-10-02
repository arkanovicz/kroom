package com.republicate.kroom.webapp.authoring

import java.util.concurrent.ConcurrentHashMap

/** A content tree in memory: tests, demos, and the shape every other store follows. */
open class MemoryResourceStore(sigil: String = "%%@") : ResourceStore(sigil) {

    private val files = ConcurrentHashMap<String, String>()

    override fun read(path: String): Block? = files[path]?.let { parse(it, path) }

    override fun write(path: String, body: String, meta: Map<String, String>): Block {
        val block = Block(path, body.trim('\n'), merge(path, meta))
        files[path] = source(block)
        onWritten(block)
        return block
    }

    override fun list(prefix: String): List<String> = files.keys.filter { it.startsWith(prefix) }.sorted()

    override fun delete(path: String): Boolean = (files.remove(path) != null).also { if (it) onDeleted(path) }

    protected open fun onDeleted(path: String) {}

    override fun move(from: String, to: String): Boolean {
        require(!files.containsKey(to)) { "a block is already at $to" }
        val source = files.remove(from) ?: return false
        files[to] = source
        onMoved(from, to)
        return true
    }

    protected open fun onMoved(from: String, to: String) {}

    /** Where a store that keeps its past records one — see [VersionedMemoryResourceStore]. */
    protected open fun onWritten(block: Block) {}
}

/**
 * The demo's history: every write kept, newest first. A real store delegates this to what already versions
 * its content (git log, a table); keeping the same [Versioned] surface is what lets the history and journal
 * routes mount over either.
 */
class VersionedMemoryResourceStore(sigil: String = "%%@") : MemoryResourceStore(sigil), Versioned {

    private val revisions = ArrayList<Pair<Revision, Block>>()

    override fun onWritten(block: Block) = synchronized(revisions) {
        revisions.add(Revision(block.rev, block.path, block.author, block.updated) to block)
        Unit
    }

    /** The past follows the block: its revisions answer under the new path, and the move is one more of them. */
    override fun onMoved(from: String, to: String) = synchronized(revisions) {
        revisions.replaceAll { (revision, block) -> if (revision.path == from) revision.copy(path = to) to block.copy(path = to) else revision to block }
        revisions.lastOrNull { it.first.path == to }?.let { (last, block) ->
            revisions.add(last.copy(time = System.currentTimeMillis(), message = "moved from $from") to block)
        }
        Unit
    }

    /** A deletion is a revision too: an empty block, so the history shows what went and restoring brings it back. */
    override fun onDeleted(path: String) = synchronized(revisions) {
        val gone = Block(path, "", mapOf("updated" to System.currentTimeMillis().toString()))
        revisions.add(Revision(gone.rev, path, null, gone.updated, "deleted") to gone)
        Unit
    }

    override fun log(path: String?, limit: Int): List<Revision> = synchronized(revisions) {
        revisions.asReversed().asSequence()
            .filter { path == null || it.first.path == path }
            .map { it.first }.take(limit).toList()
    }

    override fun read(path: String, rev: String): Block? = synchronized(revisions) {
        revisions.lastOrNull { it.first.path == path && it.first.rev == rev }?.second
    }
}
