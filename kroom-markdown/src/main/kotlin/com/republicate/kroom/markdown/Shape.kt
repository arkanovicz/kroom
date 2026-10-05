package com.republicate.kroom.markdown

import org.apache.velocity.engine.jvm.Navigation
import java.time.temporal.Temporal
import java.util.Date
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.starProjectedType

/**
 * What a block may write after `$`, then after each `.` — for an editor's completion, one level per call:
 * `unfold([])` names the block's arguments and tools, `unfold(["club", "membres()", "[]"])` what a member of
 * `$club.membres()` offers.
 *
 * Built from the arguments of one render, it keeps types, never values: a map handed by the page is the one
 * value-shaped thing (its keys live in the value), and is read once, here. Past that, every level is the
 * engine's own [Navigation] under the block sandbox — what is offered is what a block may write.
 *
 * A level maps each key to what lies behind it: a leaf's type name (`"String"`), `{}` for something to unfold,
 * `[x]` for something to iterate over (`%foreach`, `[]` in a path), `x` saying what one element is; `"*"` for
 * any key of a map. A call's key spells its required parameters: `greet(name)`.
 */
internal class Shape(private val navigation: Navigation, arguments: Map<String, Any?>) {

    private sealed interface Node
    private class Typed(val type: KType) : Node
    private class Keys(val entries: Map<String, Node>) : Node
    private class Items(val element: Node) : Node

    private val root = Keys(arguments.mapNotNull { (k, v) -> nodeOf(v, mutableSetOf())?.let { k to it } }.toMap())

    fun unfold(steps: List<String>): Map<String, Any>? {
        var node: Node = root
        for (step in steps) node = step(node, step) ?: return null
        return level(node)
    }

    private fun nodeOf(value: Any?, seen: MutableSet<Any>): Node? = when {
        value == null -> null
        value is Map<*, *> -> if (!seen.add(value)) null else Keys(
            value.entries.mapNotNull { (k, v) -> nodeOf(v, seen)?.let { k.toString() to it } }.toMap()
        )
        // a materialized list says what it holds by its first element; a lazy sequence is not walked (it may query)
        value is List<*> -> value.firstOrNull { it != null }?.let { nodeOf(it, seen) }?.let { Items(it) }
            ?: Typed(value::class.starProjectedType)
        else -> Typed(value::class.starProjectedType)
    }

    private fun step(node: Node, step: String): Node? = when (node) {
        is Keys -> node.entries[step]
        is Items -> if (step == "[]") node.element else null
        is Typed -> if (step == "[]") navigation.elementType(node.type)?.let(::Typed) else {
            val members = navigation.navigable(node.type)
            members?.byKey?.let(::Typed) ?: members?.members?.firstOrNull { offered(it) && keyOf(it) == step }?.type?.let(::Typed)
        }
    }

    private fun level(node: Node): Map<String, Any> = when (node) {
        is Keys -> node.entries.mapValues { describe(it.value) }
        is Items -> emptyMap()
        is Typed -> {
            val members = navigation.navigable(node.type)
            when {
                members == null -> emptyMap()
                // a map navigates by key: its own methods would only hide that
                members.byKey != null -> mapOf("*" to describe(Typed(members.byKey!!)))
                else -> members.members.filter(::offered).associateBy(::keyOf) { describe(Typed(it.type)) }
            }
        }
    }

    private fun describe(node: Node): Any = when (node) {
        is Keys -> emptyMap<String, Any>()
        is Items -> listOf(describe(node.element))
        is Typed -> {
            val k = node.type.classifier as? KClass<*>
            when {
                k == null || k == Any::class -> "Any"
                LEAVES.any { k.isSubclassOf(it) } -> k.simpleName ?: "Any"
                navigation.navigable(node.type)?.byKey != null -> emptyMap<String, Any>()
                else -> navigation.elementType(node.type)?.let { listOf(describe(Typed(it))) } ?: emptyMap<String, Any>()
            }
        }
    }

    companion object {
        /** What prose prints rather than navigates. */
        private val LEAVES = listOf(CharSequence::class, Number::class, Boolean::class, Char::class, Enum::class, Temporal::class, Date::class)

        /** Listed by the engine, of no use in prose: what returns nothing, a property's accessor spelling, `Any`'s
         *  own and a data class's plumbing. Relevance only — what the sandbox refuses is never listed at all. */
        private fun offered(m: Navigation.Member) =
            m.preferred && !(m.call && m.type.classifier == Unit::class) &&
                m.name !in setOf("equals", "hashCode", "toString", "copy") && !COMPONENT.matches(m.name)

        private val COMPONENT = Regex("component\\d+")

        private fun keyOf(m: Navigation.Member) =
            if (!m.call) m.name else m.params.filterNot { it.optional }.joinToString(", ", "${m.name}(", ")") { it.name ?: "_" }
    }
}
