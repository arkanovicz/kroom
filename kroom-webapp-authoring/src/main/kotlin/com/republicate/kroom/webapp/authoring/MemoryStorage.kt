package com.republicate.kroom.webapp.authoring

import com.republicate.kson.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

/** Everything in memory — tests and demos. Content remembers its revisions unless told otherwise. */
class MemoryStorage(override val content: ResourceStore = VersionedMemoryResourceStore()) : Storage {

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
