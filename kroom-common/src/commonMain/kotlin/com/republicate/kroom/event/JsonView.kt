package com.republicate.kroom.event

import com.republicate.kson.Json
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * A typed view over a JSON object: the JSON stays the truth, on every target, and a property
 * declared `by string()` reads and writes the key of its own name. Declaring a field records it,
 * so a view knows its own shape at runtime without reflection — that is the catalog.
 *
 * A field is `in` (a client may send it, validated) or `out` (the loop fills it, a client may
 * not); public, or selective (`visibleTo`: a rule over the viewer, so it reads nullable; `hidden`: nobody);
 * `required` refuses its absence.
 */
abstract class JsonView(val json: Json.MutableObject) {
    private val declared = mutableListOf<Field<*>>()

    /** The declaration, in declaration order. */
    val fields: List<Field<*>> get() = declared

    /**
     * What [viewer] (a user id, null for nobody in particular) sees of the JSON, at any depth: the selective fields
     * whose rule refuses it removed. What a frame and a snapshot carry; the JSON itself when nothing is selective.
     */
    fun view(viewer: String?): Json.Object {
        if (!selective) return json
        val out = Json.MutableObject()
        for ((k, v) in json) {
            val f = declared.firstOrNull { it.name == k }
            if (f == null) out[k] = v else if (f.visibleTo?.invoke(viewer) != false) out[k] = f.view(v, viewer)
        }
        return out
    }

    /** A selective field declared here or in a nested view: its views differ by viewer. */
    val selective: Boolean get() = declared.any { it.visibleTo != null || it.nested?.selective == true }

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
        Field<V?>(Kind.OBJECT, { j, k -> j.getObject(k)?.let { view(it as Json.MutableObject) } }, { it?.json }, nested = Nested(Shape.ONE, view))

    /** Views by their `id`, in insertion order; on the wire an object keyed by id. Absent reads empty. */
    protected fun <V : JsonView> keyed(view: (Json.MutableObject) -> V) =
        Field<Keyed<V>>(Kind.OBJECT, { j, k ->
            val obj = j.getObject(k) as Json.MutableObject? ?: Json.MutableObject().also { (j as Json.MutableObject)[k] = it }
            Keyed(obj, view)
        }, { it.obj }, nested = Nested(Shape.KEYED, view))

    /** An append-only list of views, the last [max] when bounded: a frame named like it appends. */
    protected fun <V : JsonView> log(view: (Json.MutableObject) -> V, max: Int = 0) =
        Field<Log<V>>(Kind.ARRAY, { j, k ->
            val arr = j.getArray(k) as Json.MutableArray? ?: Json.MutableArray().also { (j as Json.MutableObject)[k] = it }
            Log(arr, view, max)
        }, { it.array }, nested = Nested(Shape.LOG, view))

    enum class Shape { ONE, KEYED, LOG }

    /** What a field of views holds: how [view] reaches the selective fields below it. */
    class Nested(val shape: Shape, val factory: (Json.MutableObject) -> JsonView) {
        internal val selective: Boolean by lazy { factory(Json.MutableObject()).selective }
        internal fun view(v: Any?, viewer: String?): Any? {
            fun one(o: Any?): Json.Object = factory(o as? Json.MutableObject ?: Json.MutableObject(o as Json.Object)).view(viewer)
            return when {
                v == null || !selective -> v
                shape == Shape.ONE -> one(v)
                shape == Shape.KEYED -> Json.MutableObject((v as Json.Object).mapValues { one(it.value) })
                else -> Json.MutableArray((v as Json.Array).map { one(it) })
            }
        }
    }

    class Field<T> @PublishedApi internal constructor(
        val kind: Kind,
        private val read: (Json.Object, String) -> T?,
        private val write: (T) -> Any? = { it },
        val mandatory: Boolean = false,
        val isOut: Boolean = false,
        /** Who sees it, by user id; null: everyone. */
        val visibleTo: ((String?) -> Boolean)? = null,
        val nested: Nested? = null
    ) {
        lateinit var name: String
            private set

        /** The same field, non-null: `by enum<Move>().required`. */
        val required: Field<T & Any> get() = Field(kind, read, write, true, isOut, visibleTo, nested)
        /** The loop fills it; a client sending it is refused. */
        val out: Field<T> get() = Field(kind, read, write, mandatory, true, visibleTo, nested)
        /**
         * Selective: in the frames and snapshots of the viewers [rule] accepts, read on the instance (`{ it == player }`);
         * absent elsewhere, so it reads nullable (`required` still refuses a posted intent without it).
         */
        @Suppress("UNCHECKED_CAST")
        fun visibleTo(rule: (viewer: String?) -> Boolean): Field<T?> = Field(kind, read, write, mandatory, isOut, rule, nested) as Field<T?>
        /** Seen by nobody: the server's alone (a deck not dealt yet), kept in the State so the rule stays a function of it. */
        val hidden: Field<T?> get() = visibleTo { false }

        operator fun provideDelegate(thisRef: JsonView, prop: KProperty<*>): ReadWriteProperty<JsonView, T> {
            name = prop.name
            thisRef.declared += this
            return object : ReadWriteProperty<JsonView, T> {
                @Suppress("UNCHECKED_CAST")
                override fun getValue(thisRef: JsonView, property: KProperty<*>): T =
                    (read(thisRef.json, name) ?: if (mandatory && visibleTo == null) throw IllegalStateException("$name: missing") else null) as T
                /** Null removes the key: absent and null read alike. */
                override fun setValue(thisRef: JsonView, property: KProperty<*>, value: T) {
                    write(value)?.let { thisRef.json[name] = it } ?: thisRef.json.remove(name)
                }
            }
        }

        internal fun view(v: Any?, viewer: String?): Any? = nested?.view(v, viewer) ?: v

        fun check(j: Json.Object): Reason? {
            val absent = !j.containsKey(name) || j[name] == null
            if (absent) return if (mandatory && !isOut) Reason("missing", Json.Object("field" to name)) else null
            if (isOut) return Reason("undeclared", Json.Object("field" to name))
            return try { read(j, name); null } catch (e: Exception) { Reason("invalid", Json.Object("field" to name, "kind" to kind.name)) }
        }

        fun toJson(): Json.Object = Json.Object("name" to name, "kind" to kind.name, "required" to mandatory, "out" to isOut, "selective" to (visibleTo != null))
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
