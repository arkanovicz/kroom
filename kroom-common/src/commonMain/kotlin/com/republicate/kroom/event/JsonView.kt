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

    /** The view this one was read from (an `obj`, `keyed` or `log` field's), so a write can tell the root which items it is under. */
    internal var owner: JsonView? = null

    /** What this item is across changes of its content: its `id`, else its `seq`, when declared and present; null when it is known by its position. */
    val identity: Any? get() = IDENTITY.firstNotNullOfOrNull { k -> if (declared.any { it.name == k }) json[k] else null }

    /** A write here: the identities from this view up to the root are dirty, the root is told. */
    internal fun touched() {
        val keys = ArrayList<Any>(2)
        var v: JsonView = this
        while (true) { v.identity?.let { keys += it }; v = v.owner ?: break }
        (v as? State)?.let { it.touched = true; it.dirty?.addAll(keys) }
    }

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
        Field<V?>(Kind.OBJECT, { j, k -> j.getObject(k)?.let { view(it as Json.MutableObject).also { v -> v.owner = this } } }, { it?.json }, nested = Nested(Shape.ONE, view))

    /** Views by their `id`, in insertion order; on the wire an object keyed by id. Absent reads empty. */
    protected fun <V : JsonView> keyed(view: (Json.MutableObject) -> V) =
        Field<Keyed<V>>(Kind.OBJECT, { j, k ->
            val obj = j.getObject(k) as Json.MutableObject? ?: Json.MutableObject().also { (j as Json.MutableObject)[k] = it }
            Keyed(obj, view, this)
        }, { it.obj }, nested = Nested(Shape.KEYED, view))

    /** An append-only list of views, the last [max] when bounded: a frame named like it appends. */
    protected fun <V : JsonView> log(view: (Json.MutableObject) -> V, max: Int = 0) =
        Field<Log<V>>(Kind.ARRAY, { j, k ->
            val arr = j.getArray(k) as Json.MutableArray? ?: Json.MutableArray().also { (j as Json.MutableObject)[k] = it }
            Log(arr, view, max, this)
        }, { it.array }, nested = Nested(Shape.LOG, view))

    /** Views in an order that is itself state, each found by its `id`; on the wire an array. Absent reads empty. */
    protected fun <V : JsonView> seq(view: (Json.MutableObject) -> V) =
        Field<Seq<V>>(Kind.ARRAY, { j, k ->
            val arr = j.getArray(k) as Json.MutableArray? ?: Json.MutableArray().also { (j as Json.MutableObject)[k] = it }
            Seq(arr, view, this)
        }, { it.array }, nested = Nested(Shape.SEQ, view))

    enum class Shape { ONE, KEYED, LOG, SEQ }

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
                    thisRef.touched()
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

        /**
         * A form's text in this kind, as early as the browser reads it: a number parsed, a checkbox's `on` true, `""` null
         * for every kind but STRING (the key stays: a field emptied on purpose, a vote undone); an ARRAY's is its items
         * separated by blanks, as html writes a list in an attribute (`class`, `rel`); a text no kind reads stays as is,
         * for [check] to refuse.
         */
        fun parse(text: String): Any? = when {
            kind == Kind.STRING -> text
            text.isEmpty() -> null
            kind == Kind.INT -> text.toIntOrNull() ?: text
            kind == Kind.LONG -> text.toLongOrNull() ?: text
            kind == Kind.DOUBLE -> text.toDoubleOrNull() ?: text
            kind == Kind.BOOL -> when (text.lowercase()) { "true", "on", "1" -> true; "false", "off", "0" -> false; else -> text }
            kind == Kind.ARRAY -> Json.MutableArray(text.split(BLANKS).filter { it.isNotEmpty() })
            else -> text
        }
    }

    /** The views over an array of objects, appended through [plusAssign], never removed but by the bound. */
    class Log<V : JsonView>(val array: Json.MutableArray, private val view: (Json.MutableObject) -> V, val max: Int = 0, private val owner: JsonView? = null) : AbstractList<V>() {
        override val size: Int get() = array.size
        override fun get(index: Int): V = view(array[index] as Json.MutableObject).also { it.owner = owner }
        operator fun plusAssign(v: V) {
            array.add(v.json)
            if (max > 0) while (array.size > max) array.removeAt(0)
            v.owner = owner
            v.touched()
        }
    }

    /** The views of an object keyed by id, iterated in insertion order: [add] on a present id updates in place, keeping its rank. */
    class Keyed<V : JsonView>(val obj: Json.MutableObject, private val view: (Json.MutableObject) -> V, private val owner: JsonView? = null) : AbstractList<V>() {
        override val size: Int get() = obj.size
        override fun get(index: Int): V = at(obj.values.elementAt(index))
        override fun iterator(): Iterator<V> = obj.values.asSequence().map { at(it) }.iterator()
        operator fun get(id: String): V? = (obj[id] as? Json.MutableObject)?.let { at(it) }
        operator fun contains(id: String): Boolean = obj.containsKey(id)
        fun add(item: V) {
            obj[item.json.getString("id") ?: throw IllegalArgumentException("no id: ${item.json}")] = item.json
            item.owner = owner
            item.touched()
        }
        fun remove(id: String) { if (obj.remove(id) != null) owner?.touched() }
        private fun at(o: Any?): V = view(o as Json.MutableObject).also { it.owner = owner }
    }

    /** The views over an array of objects whose order is state: [splice] is its one writer. */
    class Seq<V : JsonView>(val array: Json.MutableArray, private val view: (Json.MutableObject) -> V, private val owner: JsonView? = null) : AbstractList<V>() {
        override val size: Int get() = array.size
        override fun get(index: Int): V = view(array[index] as Json.MutableObject).also { it.owner = owner }
        operator fun get(id: String): V? = indexOf(id).takeIf { it >= 0 }?.let { get(it) }
        operator fun contains(id: String): Boolean = indexOf(id) >= 0
        /** Where the item of that [id] stands, -1 when none does. */
        fun indexOf(id: String): Int = array.indexOfFirst { (it as Json.Object).getString("id") == id }
        /** [count] items from [index] give way to [items], in their place: an insertion, a removal, a rewrite. */
        fun splice(index: Int, count: Int, items: List<V> = emptyList()) {
            items.firstOrNull { it.json.getString("id") == null }?.let { throw IllegalArgumentException("no id: ${it.json}") }
            repeat(count) { array.removeAt(index) }
            items.forEachIndexed { i, item -> array.add(index + i, item.json) }
            for (item in items) { item.owner = owner; item.touched() }
            if (count > 0 && items.isEmpty()) owner?.touched()
        }
    }
}

/** The fields that name an item, in the order tried. */
private val IDENTITY = listOf("id", "seq")
private val BLANKS = Regex("""\s+""")
