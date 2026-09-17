package com.republicate.kroom.webapp.authoring

import org.apache.velocity.util.ExtProperties
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
 * Configurable either way: handed as `markdown.resource.loader.<name>.instance` (the application then holds
 * the same object the editor writes through), or by `…​.path` when only reading matters.
 */
open class FileResourceStore(private var root: Path = Path.of("data/content"), sigil: String = "%%@") : ResourceStore(sigil) {

    override fun init(configuration: ExtProperties) {
        configuration.getString("path")?.let { root = Path.of(it) }
    }

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
