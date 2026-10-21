package com.republicate.kroom.spike

import com.republicate.kroom.event.Event
import kotlin.properties.ReadOnlyProperty
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/*
 * Spec §2 (private/eventsv3.md): a State is one class, common; its fields have a kind and a
 * storage, JSON on the server, the DOM in the browser, never both. The State and its `reduce`
 * are written against the fields; the storage is handed in.
 */

/** The scalar fields of one object, by name: a JSON object on the server, an item's sites in the browser. */
interface Values {
    operator fun get(name: String): String?
    operator fun set(name: String, value: String?)
}

/** A field kind: insertion-ordered, addressed by key, iterated as values. `add` on a present key updates it in place. */
interface Keyed<V : View> : Iterable<V> {
    val size: Int
    operator fun get(key: String): V?
    fun add(key: String, values: Map<String, String?>)
    fun remove(key: String)
    fun clear()
}

/** Where a State's fields live. A field the storage does not hold (the page does not read it) is [Absent]. */
interface Storage {
    fun <V : View> keyed(name: String, view: (Values) -> V): Keyed<V>
}

/** A typed view over one object's values; a property `by string()` reads and writes the value of its own name. */
abstract class View(val values: Values) {
    protected fun string(): ReadWriteProperty<View, String?> = object : ReadWriteProperty<View, String?> {
        override fun getValue(thisRef: View, property: KProperty<*>) = thisRef.values[property.name]
        override fun setValue(thisRef: View, property: KProperty<*>, value: String?) { thisRef.values[property.name] = value }
    }
}

/** The room's data: fields by delegates, the storage handed in, one `reduce` for every storage. */
abstract class State(val storage: Storage) {
    protected fun <V : View> keyed(view: (Values) -> V) = KeyedDeclaration(view)

    class KeyedDeclaration<V : View>(private val view: (Values) -> V) {
        operator fun provideDelegate(thisRef: State, prop: KProperty<*>): ReadOnlyProperty<State, Keyed<V>> {
            val keyed = thisRef.storage.keyed(prop.name, view)
            return ReadOnlyProperty { _, _ -> keyed }
        }
    }

    /** One frame into the fields: the only writer. */
    abstract fun reduce(frame: Event)
}

/** The partial view's null object: a field the page does not read. Writes are nothing, reads are empty. */
class Absent<V : View> : Keyed<V> {
    override val size get() = 0
    override fun get(key: String): V? = null
    override fun add(key: String, values: Map<String, String?>) {}
    override fun remove(key: String) {}
    override fun clear() {}
    override fun iterator() = emptyList<V>().iterator()
}
