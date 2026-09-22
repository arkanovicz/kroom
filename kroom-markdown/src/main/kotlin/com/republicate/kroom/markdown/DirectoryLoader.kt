package com.republicate.kroom.markdown

import org.apache.velocity.engine.ResourceLoader
import org.apache.velocity.engine.ResourceNotFoundException
import java.nio.file.Files
import java.nio.file.Path

/** Blocks from a directory, read-only — for a site that renders markdown nobody edits in place. */
class DirectoryLoader(private val root: Path) : ResourceLoader {

    private fun resolve(name: String): Path {
        val path = root.resolve(name.trimStart('/')).normalize()
        if (!path.startsWith(root.normalize())) throw ResourceNotFoundException("outside the content root: $name")
        return path
    }

    override fun load(name: String): String {
        val path = resolve(name)
        if (!Files.isRegularFile(path)) throw ResourceNotFoundException("no such block: $name")
        return Files.readString(path)
    }

    override fun exists(name: String): Boolean = Files.isRegularFile(resolve(name))

    override fun lastModified(name: String): Long? =
        resolve(name).takeIf { Files.isRegularFile(it) }?.let { Files.getLastModifiedTime(it).toMillis() }
}
