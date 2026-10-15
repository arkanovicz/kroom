package com.republicate.kroom.event

import com.republicate.kson.Json
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * A typed view over a JSON object: the JSON stays the truth, on every target, and a property
 * declared `by string()` reads and writes the key of its own name. Declaring a field records it,
 * so a view knows its own shape at runtime without reflection — that is the catalog.
 */
abstract class JsonView(val json: Json.MutableObject) {
    private val declared = mutableListOf<Field<*>>()

    /** The declaration, in declaration order. */
    val fields: List<Field<*>> get() = declared

    /** Required fields present, every present field of its kind, no key beyond the declared; null when well-formed. */
    fun validate(): Reason? = declared.firstNotNullOfOrNull { it.check(json) }
        ?: json.keys.firstOrNull { k -> declared.none { it.name == k } }?.let { Reason("undeclared", Json.Object("field" to it)) }

    enum class Kind { STRING, INT, LONG, DOUBLE, BOOL, OBJECT, ARRAY }

    protected fun string() = Field<String?>(Kind.STRING, false, { j, k -> j.getString(k) })
    protected fun int() = Field<Int?>(Kind.INT, false, { j, k -> j.getInt(k) })
    protected fun long() = Field<Long?>(Kind.LONG, false, { j, k -> j.getLong(k) })
    protected fun double() = Field<Double?>(Kind.DOUBLE, false, { j, k -> j.getDouble(k) })
    protected fun bool() = Field<Boolean?>(Kind.BOOL, false, { j, k -> j.getBoolean(k) })
    protected fun obj() = Field<Json.Object?>(Kind.OBJECT, false, { j, k -> j.getObject(k) })
    protected fun array() = Field<Json.Array?>(Kind.ARRAY, false, { j, k -> j.getArray(k) })
    protected inline fun <reified E : Enum<E>> enum() =
        Field<E?>(Kind.STRING, false, { j, k -> j.getString(k)?.let { enumValueOf<E>(it) } }, { it?.name })

    class Field<T> @PublishedApi internal constructor(
        val kind: Kind,
        val mandatory: Boolean,
        private val read: (Json.Object, String) -> T?,
        private val write: (T) -> Any? = { it }
    ) {
        lateinit var name: String
            private set

        /** The same field, non-null: `by enum<Move>().required`. */
        val required: Field<T & Any> get() = Field(kind, true, read, write)

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
            if (absent) return if (mandatory) Reason("missing", Json.Object("field" to name)) else null
            return try { read(j, name); null } catch (e: Exception) { Reason("invalid", Json.Object("field" to name, "kind" to kind.name)) }
        }

        fun toJson(): Json.Object = Json.Object("name" to name, "kind" to kind.name, "required" to mandatory)
    }
}
