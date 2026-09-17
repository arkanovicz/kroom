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

    override fun log(path: String?, limit: Int): List<Revision> = synchronized(revisions) {
        revisions.asReversed().asSequence()
            .filter { path == null || it.first.path == path }
            .map { it.first }.take(limit).toList()
    }

    override fun read(path: String, rev: String): Block? = synchronized(revisions) {
        revisions.lastOrNull { it.first.path == path && it.first.rev == rev }?.second
    }
}
