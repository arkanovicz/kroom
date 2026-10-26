package com.republicate.kroom.event

import com.republicate.kson.Json
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * A typed view over a JSON object: the JSON stays the truth, on every target, and a property
 * declared `by string()` reads and writes the key of its own name. Declaring a field records it,
 * so a view knows its own shape at runtime without reflection — that is the registry.
 *
 * A field is `in` (a client may send it, validated) or `out` (the loop fills it, a client may
 * not); `mine` marks one that reaches its author's tabs only; `required` refuses its absence.
 */
abstract class JsonView(val json: Json.MutableObject) {
    private val declared = mutableListOf<Field<*>>()

    /** The declaration, in declaration order. */
    val fields: List<Field<*>> get() = declared

    /** The keys of the fields the author alone sees. */
    val mine: List<String> get() = declared.filter { it.isMine }.map { it.name }

    /** Required fields present, every present field of its kind, no key beyond the declared `in`; null when well-formed. */
    fun validate(): Reason? = declared.firstNotNullOfOrNull { it.check(json) }
        ?: json.keys.firstOrNull { k -> declared.none { it.name == k && !it.isOut } }?.let { Reason("undeclared", Json.Object("field" to it)) }

    enum class Kind { STRING, INT, LONG, DOUBLE, BOOL, OBJECT, ARRAY }

    protected fun string() = Field<String?>(Kind.STRING, { j, k -> j.getString(k) })
    protected fun int() = Field<Int?>(Kind.INT, { j, k -> j.getInt(k) })
    protected fun long() = Field<Long?>(Kind.LONG, { j, k -> j.getLong(k) })
    protected fun double() = Field<Double?>(Kind.DOUBLE, { j, k -> j.getDouble(k) })
    protected fun bool() = Field<Boolean?>(Kind.BOOL, { j, k -> j.getBoolean(k) })
    protected fun obj() = Field<Json.Object?>(Kind.OBJECT, { j, k -> j.getObject(k) })
    protected fun array() = Field<Json.Array?>(Kind.ARRAY, { j, k -> j.getArray(k) })
    /** Read whatever the case (a button says `rock`), written as the constant's name. */
    protected inline fun <reified E : Enum<E>> enum() =
        Field<E?>(Kind.STRING, { j, k -> j.getString(k)?.let { s -> enumValues<E>().firstOrNull { it.name.equals(s, ignoreCase = true) } ?: throw IllegalArgumentException(s) } }, { it?.name })

    /** A nested view, built over the object under the key (which stays the truth); null when absent. */
    protected fun <V : JsonView> obj(view: (Json.MutableObject) -> V) =
        Field<V?>(Kind.OBJECT, { j, k -> j.getObject(k)?.let { view(it as Json.MutableObject) } }, { it?.json })

    /** Views by their `id`, in insertion order; on the wire an object keyed by id. Absent reads empty. */
    protected fun <V : JsonView> keyed(view: (Json.MutableObject) -> V) =
        Field<Keyed<V>>(Kind.OBJECT, { j, k ->
            val obj = j.getObject(k) as Json.MutableObject? ?: Json.MutableObject().also { (j as Json.MutableObject)[k] = it }
            Keyed(obj, view)
        }, { it.obj })

    /** An append-only list of views, the last [max] when bounded: a frame named like it appends. */
    protected fun <V : JsonView> log(view: (Json.MutableObject) -> V, max: Int = 0) =
        Field<Log<V>>(Kind.ARRAY, { j, k ->
            val arr = j.getArray(k) as Json.MutableArray? ?: Json.MutableArray().also { (j as Json.MutableObject)[k] = it }
            Log(arr, view, max)
        }, { it.array })

    class Field<T> @PublishedApi internal constructor(
        val kind: Kind,
        private val read: (Json.Object, String) -> T?,
        private val write: (T) -> Any? = { it },
        val mandatory: Boolean = false,
        val isOut: Boolean = false,
        val isMine: Boolean = false
    ) {
        lateinit var name: String
            private set

        /** The same field, non-null: `by enum<Move>().required`. */
        val required: Field<T & Any> get() = Field(kind, read, write, true, isOut, isMine)
        /** The loop fills it; a client sending it is refused. */
        val out: Field<T> get() = Field(kind, read, write, mandatory, true, isMine)
        /** Its author's tabs alone see it on the wire. */
        val mine: Field<T> get() = Field(kind, read, write, mandatory, isOut, true)

        operator fun provideDelegate(thisRef: JsonView, prop: KProperty<*>): ReadWriteProperty<JsonView, T> {
            name = prop.name
            thisRef.declared += this
            return object : ReadWriteProperty<JsonView, T> {
                @Suppress("UNCHECKED_CAST")
                override fun getValue(thisRef: JsonView, property: KProperty<*>): T =
                    (read(thisRef.json, name) ?: if (mandatory) throw IllegalStateException("$name: missing") else null) as T
                override fun setValue(thisRef: JsonView, property: KProperty<*>, value: T) { thisRef.json[name] = write(value) }
            }
        }

        fun check(j: Json.Object): Reason? {
            val absent = !j.containsKey(name) || j[name] == null
            if (absent) return if (mandatory && !isOut) Reason("missing", Json.Object("field" to name)) else null
            if (isOut) return Reason("undeclared", Json.Object("field" to name))
            return try { read(j, name); null } catch (e: Exception) { Reason("invalid", Json.Object("field" to name, "kind" to kind.name)) }
        }

        fun toJson(): Json.Object = Json.Object("name" to name, "kind" to kind.name, "required" to mandatory, "out" to isOut, "mine" to isMine)
    }

    /** The views over an array of objects, appended through [plusAssign], never removed but by the bound. */
    class Log<V : JsonView>(val array: Json.MutableArray, private val view: (Json.MutableObject) -> V, val max: Int = 0) : AbstractList<V>() {
        override val size: Int get() = array.size
        override fun get(index: Int): V = view(array[index] as Json.MutableObject)
        operator fun plusAssign(v: V) {
            array.add(v.json)
            if (max > 0) while (array.size > max) array.removeAt(0)
        }
    }

    /** The views of an object keyed by id, iterated in insertion order: [add] on a present id updates in place, keeping its rank. */
    class Keyed<V : JsonView>(val obj: Json.MutableObject, private val view: (Json.MutableObject) -> V) : AbstractList<V>() {
        override val size: Int get() = obj.size
        override fun get(index: Int): V = view(obj.values.elementAt(index) as Json.MutableObject)
        override fun iterator(): Iterator<V> = obj.values.asSequence().map { view(it as Json.MutableObject) }.iterator()
        operator fun get(id: String): V? = (obj[id] as? Json.MutableObject)?.let(view)
        operator fun contains(id: String): Boolean = obj.containsKey(id)
        fun add(item: V) { obj[item.json.getString("id") ?: throw IllegalArgumentException("no id: ${item.json}")] = item.json }
        fun remove(id: String) { obj.remove(id) }
    }
}
