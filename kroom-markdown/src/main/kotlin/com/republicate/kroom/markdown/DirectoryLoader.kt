package com.republicate.kroom.markdown

import kotlinx.io.Source
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.apache.velocity.engine.ResourceNotFoundException
import org.apache.velocity.engine.resource.Content
import org.apache.velocity.engine.resource.ResourceLoader
import java.nio.file.Files
import java.nio.file.Path

/** Blocks from a directory, read-only — for a site that renders markdown nobody edits in place. */
class DirectoryLoader(private val root: Path) : ResourceLoader {

    private fun resolve(name: String): Path {
        val path = root.resolve(name.trimStart('/')).normalize()
        if (!path.startsWith(root.normalize())) throw ResourceNotFoundException("outside the content root: $name")
        return path
    }

    override fun find(name: String): Content? {
        val path = resolve(name).takeIf { Files.isRegularFile(it) } ?: return null
        val stamp = Files.getLastModifiedTime(path).toMillis()
        return object : Content() {
            override fun open(): Source = Files.newInputStream(path).asSource().buffered()
            override fun isModified(): Boolean = !Files.isRegularFile(path) || Files.getLastModifiedTime(path).toMillis() != stamp
        }
    }
}
