package com.republicate.kroom.webapp.authoring

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText

/**
 * A content tree on disk, under [root]. No history of its own: the history routes stay unmounted unless an
 * application subclasses it into something [Versioned] — the git-backed store site2026 needs lives there,
 * app-side, with JGit and the commit policy it wants.
 *
 * Handed to the `%` engine as `markdown.loader`: the application holds the very object the editor writes
 * through.
 */
open class FileResourceStore(private val root: Path = Path.of("data/content"), sigil: String = "%%@") : ResourceStore(sigil) {

    protected fun resolve(path: String): Path {
        val resolved = root.resolve(path).normalize()
        require(resolved.startsWith(root.normalize())) { "path escapes the content root: '$path'" }
        return resolved
    }

    override fun read(path: String): Block? =
        resolve(path).takeIf { it.isRegularFile() }?.let { parse(it.readText(), path) }

    override fun write(path: String, body: String, meta: Map<String, String>): Block {
        val block = Block(path, body.trim('\n'), merge(path, meta))
        resolve(path).createParentDirectories().writeText(source(block))
        return block
    }

    override fun delete(path: String): Boolean = Files.deleteIfExists(resolve(path))

    override fun move(from: String, to: String): Boolean {
        val source = resolve(from).takeIf { it.isRegularFile() } ?: return false
        val target = resolve(to)
        require(!Files.exists(target)) { "a block is already at $to" }
        Files.move(source, target.createParentDirectories())
        return true
    }

    override fun list(prefix: String): List<String> {
        if (!Files.isDirectory(root)) return emptyList()
        Files.walk(root).use { paths ->
            return paths.filter { it.isRegularFile() }
                .map { it.relativeTo(root).invariantSeparatorsPathString }
                .filter { it.startsWith(prefix) }
                .sorted().toList()
        }
    }
}
