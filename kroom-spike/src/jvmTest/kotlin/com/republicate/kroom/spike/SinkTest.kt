package com.republicate.kroom.spike

import org.apache.velocity.engine.Compiler
import org.apache.velocity.engine.Config
import org.apache.velocity.engine.Evaluable
import org.apache.velocity.engine.Expr
import org.apache.velocity.engine.VelocityEngine
import org.apache.velocity.engine.jvm.ScriptingCompiler
import org.apache.velocity.engine.resource.StockResourceLoader
import velocity.templates.VelocityTemplates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The scenario over the tree double, on the interpreted pipeline and on the compiled class. */
class SinkTest {
    private val page = SinkTest::class.java.getResource("/vtl/page.vtl")!!.readText()

    private fun scenario(engine: VelocityEngine, text: (org.apache.velocity.engine.Context) -> String, render: (org.apache.velocity.engine.Context, Appendable) -> Unit): Pair<Scenario<DNode>, TreeDouble> {
        val tree = TreeDouble()
        val root = tree.element("body")
        val logs = HashMap<String, List<String>>()
        val s = Scenario(engine, tree, root,
            html = { html(root.children) },
            items = { (root.children.first { it is DElement && it.tag == "ul" } as DElement).children.filterIsInstance<DElement>() },
            attribute = { n, a -> (n as DElement).attributes[a] },
            text = text, render = render,
            afterFrame = { name -> logs[name] = tree.log.toList(); tree.clearLog() })
        tree.clearLog()
        return s to tree.also { it.asDynamicLogs = logs }
    }

    private fun check(logs: Map<String, List<String>>) {
        val status = logs["status"]!!
        assertEquals(2, status.size, status.joinToString("\n"))
        assertTrue(status.any { it.startsWith("setAttribute") && "user AWAY" in it })
        assertTrue(status.any { it.startsWith("setText") && "AWAY" in it })
        assertTrue(logs["join"]!!.none { it.startsWith("remove") })
        assertTrue(logs["leave"]!!.none { it.startsWith("element") })
    }

    @Test fun interpreted() {
        val engine = VelocityEngine(ScriptingCompiler(), Config(informalNavigation = true))
        val (s, tree) = scenario(engine, { engine.evaluate(page, it, "page") }, { c, out -> engine.evaluate(page, c, out, "page") })
        s.run(); check(tree.asDynamicLogs)
    }

    @Test fun compiled() {
        val engine = VelocityEngine(NoCompiler, Config(informalNavigation = true)).apply { addResourceLoader("stock", StockResourceLoader(VelocityTemplates::resolve)) }
        val (s, tree) = scenario(engine, { engine.getTemplate("page.vtl").merge(it) }, { c, out -> engine.getTemplate("page.vtl").merge(c, out) })
        s.run(); check(tree.asDynamicLogs)
    }

    private object NoCompiler : Compiler {
        override fun compile(expr: Expr): Evaluable = throw IllegalStateException("compiled pipeline: unexpected expression ${expr.source}")
    }
}
