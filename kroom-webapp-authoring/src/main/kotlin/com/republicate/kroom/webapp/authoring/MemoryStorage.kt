package com.republicate.kroom.webapp.authoring

import com.republicate.kson.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Everything in memory — tests and demos. Content remembers its revisions unless told otherwise; media gets
 * [mediaCapacity] bytes, and refuses what would not fit rather than forget a picture a page still shows.
 */
class MemoryStorage(
    override val content: ResourceStore = VersionedMemoryResourceStore(),
    mediaCapacity: Long = 100L * 1024 * 1024
) : Storage {

    override val media: Media = MemoryMedia(mediaCapacity)

    private val settings = ConcurrentHashMap<String, MemorySettings>()
    private val records = ConcurrentHashMap<String, MemoryRecords>()

    override fun settings(namespace: String): Settings =
        settings.computeIfAbsent(segment(namespace)) { MemorySettings() }

    override fun records(namespace: String, collection: String): Records =
        records.computeIfAbsent("${segment(namespace)}/${segment(collection)}") { MemoryRecords() }
}

private class MemorySettings : Settings {
    private val values = ConcurrentHashMap<String, String>()
    override fun get(key: String) = values[key]
    override fun set(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    override fun all(): Map<String, String> = values.toSortedMap()
}

private class MemoryRecords : Records {
    private val rows = ConcurrentSkipListMap<String, Json.Object>()
    override fun add(record: Json.Object) = newRecordId().also { rows[it] = record }
    override fun put(id: String, record: Json.Object) { rows[id] = record }
    override fun get(id: String) = rows[id]
    override fun delete(id: String) = rows.remove(id) != null
    override fun list(limit: Int): Map<String, Json.Object> =
        rows.descendingMap().entries.asSequence().take(limit).associateTo(LinkedHashMap()) { it.key to it.value }
}

private class MemoryMedia(private val capacity: Long) : Media {
    private val files = ConcurrentSkipListMap<String, Pair<MediaFile, ByteArray>>()
    private val used = AtomicLong()

    override fun put(name: String, type: String, bytes: ByteArray): MediaFile {
        val size = bytes.size.toLong()
        if (used.addAndGet(size) > capacity) {
            used.addAndGet(-size)
            throw MediaFullException(size)
        }
        val file = MediaFile(mediaName(name, type), type, size, System.currentTimeMillis())
        files[file.name] = file to bytes
        return file
    }

    override fun get(name: String) = files[name]?.first
    override fun read(name: String) = files[name]?.second
    override fun delete(name: String) = files.remove(name)?.also { used.addAndGet(-it.first.size) } != null
    override fun list(limit: Int) = files.descendingMap().values.asSequence().take(limit).map { it.first }.toList()
}
