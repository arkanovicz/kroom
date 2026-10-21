package com.republicate.kroom.spike

import com.republicate.kson.Json

/** The server's storage: a JSON tree, which is the snapshot. Platform-free, so a test may use it anywhere. */
class JsonStorage(val json: Json.MutableObject = Json.MutableObject()) : Storage {
    override fun <V : View> keyed(name: String, view: (Values) -> V): Keyed<V> = JsonKeyed(obj(name), view)

    private fun obj(name: String) = json[name] as? Json.MutableObject ?: Json.MutableObject().also { json[name] = it }
}

class JsonValues(private val obj: Json.MutableObject) : Values {
    override fun get(name: String) = obj.getString(name)
    override fun set(name: String, value: String?) { if (value == null) obj.remove(name) else obj[name] = value }
}

/** A keyed list as a JSON object keyed by key (kson keeps insertion order). */
class JsonKeyed<V : View>(private val obj: Json.MutableObject, private val view: (Values) -> V) : Keyed<V> {
    override val size get() = obj.size
    override fun get(key: String) = (obj[key] as? Json.MutableObject)?.let { view(JsonValues(it)) }
    override fun add(key: String, values: Map<String, String?>) {
        val item = obj[key] as? Json.MutableObject ?: Json.MutableObject().also { obj[key] = it }
        val target = JsonValues(item)
        values.forEach { (k, v) -> target[k] = v }
    }
    override fun remove(key: String) { obj.remove(key) }
    override fun clear() { obj.clear() }
    override fun iterator() = obj.values.map { view(JsonValues(it as Json.MutableObject)) }.iterator()
}
