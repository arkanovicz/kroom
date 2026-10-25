package com.republicate.kroom.spike

import org.apache.velocity.engine.Context
import org.apache.velocity.engine.Merge
import org.apache.velocity.engine.VtlArg
import org.apache.velocity.engine.VtlLazyMacro

/**
 * The two block macros of the spike, standing in for `#foreach` and `#if` until the engine tells the sink
 * its block boundaries itself: `#@each($list, $var)…#end` and `#@when($cond)…#end`. Lazy, so the loop
 * variable's name is read as authored and never evaluated. With no sink in force they render plainly.
 */
class Each(private val sink: () -> DomSink<*>?, private val keyOf: (Any?) -> String) : VtlLazyMacro {
    override fun render(args: List<VtlArg>, bodyContent: ((Appendable) -> Unit)?, context: Context, out: Appendable, scope: Merge) {
        val name = args[1].literal.trim().removePrefix("$").removePrefix("!").removeSurrounding("{", "}")
        val s = sink()
        s?.enterBlock()
        for (item in iterable(args[0].eval())) {
            context[name] = item
            s?.enterItem(keyOf(item))
            bodyContent?.invoke(out)
            s?.exitItem()
        }
        s?.exitBlock()
    }

    private fun iterable(v: Any?): Iterable<Any?> = when (v) {
        null -> emptyList()
        is Iterable<*> -> v
        is Array<*> -> v.asList()
        is Map<*, *> -> v.values
        else -> error("#@each over a ${v::class.simpleName}")
    }
}

class When(private val sink: () -> DomSink<*>?) : VtlLazyMacro {
    override fun render(args: List<VtlArg>, bodyContent: ((Appendable) -> Unit)?, context: Context, out: Appendable, scope: Merge) {
        val on = when (val v = args[0].eval()) { null -> false; is Boolean -> v; is String -> v.isNotEmpty(); is Collection<*> -> v.isNotEmpty(); else -> true }
        val s = sink()
        s?.enterBlock()
        if (on) { s?.enterItem(""); bodyContent?.invoke(out); s?.exitItem() }
        s?.exitBlock()
    }
}
