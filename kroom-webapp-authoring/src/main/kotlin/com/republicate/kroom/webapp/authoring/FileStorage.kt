package com.republicate.kroom.webapp.authoring

import com.republicate.kson.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.reader
import kotlin.io.path.writeText
import kotlin.io.path.writer

/**
 * Everything on disk under [root], in plain files an operator can read and a `git` can version:
 *
 * ```
 * <root>/content/…                         the blocks (FileResourceStore)
 * <root>/settings/<namespace>.properties
 * <root>/records/<namespace>/<collection>/<id>.json
 * ```
 */
class FileStorage(
    private val root: Path = Path.of("data"),
    override val content: ResourceStore = FileResourceStore(root.resolve("content"))
) : Storage {

    override fun settings(namespace: String): Settings =
        FileSettings(root.resolve("settings").resolve("${segment(namespace)}.properties"))

    override fun records(namespace: String, collection: String): Records =
        FileRecords(root.resolve("records").resolve(segment(namespace)).resolve(segment(collection)))
}

// one lock for every settings file: writes are rare, and a read-modify-write must not interleave
private val settingsLock = Any()

private class FileSettings(private val file: Path) : Settings {

    private fun load() = Properties().apply { if (file.isRegularFile()) file.reader().use { load(it) } }

    override fun get(key: String): String? = load().getProperty(key)

    override fun set(key: String, value: String?) = synchronized(settingsLock) {
        val properties = load()
        if (value == null) properties.remove(key) else properties.setProperty(key, value)
        file.createParentDirectories().writer().use { properties.store(it, null) }
    }

    override fun all(): Map<String, String> = load().let { p -> p.stringPropertyNames().sorted().associateWith { p.getProperty(it) } }
}

private class FileRecords(private val dir: Path) : Records {

    private fun file(id: String): Path {
        require(id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "invalid record id: '$id'" }
        return dir.resolve("$id.json")
    }

    override fun add(record: Json.Object) = newRecordId().also { put(it, record) }

    override fun put(id: String, record: Json.Object) { file(id).createParentDirectories().writeText(record.toString()) }

    override fun get(id: String): Json.Object? = file(id).takeIf { it.isRegularFile() }?.let { Json.parse(it.readText()) as? Json.Object }

    override fun delete(id: String) = file(id).deleteIfExists()

    override fun list(limit: Int): Map<String, Json.Object> {
        if (!Files.isDirectory(dir)) return emptyMap()
        return dir.listDirectoryEntries("*.json").map { it.name.removeSuffix(".json") }
            .sortedDescending().take(limit)
            .mapNotNull { id -> get(id)?.let { id to it } }
            .toMap(LinkedHashMap())
    }
}
