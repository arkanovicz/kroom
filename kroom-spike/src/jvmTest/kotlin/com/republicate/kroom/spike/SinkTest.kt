package com.republicate.kroom.spike

import org.apache.velocity.engine.Compiler
import org.apache.velocity.engine.Config
import org.apache.velocity.engine.Context
import org.apache.velocity.engine.Evaluable
import org.apache.velocity.engine.Expr
import org.apache.velocity.engine.VelocityEngine
import org.apache.velocity.engine.jvm.ScriptingCompiler
import org.apache.velocity.engine.resource.ResourceLoader
import org.apache.velocity.engine.resource.StockResourceLoader
import org.apache.velocity.engine.resource.content
import velocity.templates.VelocityTemplates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The scenario over the tree double, on the interpreted pipeline and on the compiled class. */
class SinkTest {
    private val page = SinkTest::class.java.getResource("/vtl/page.vtl")!!.readText()

    private fun scenario(engine: VelocityEngine): Pair<Scenario<DNode>, TreeDouble> {
        val tree = TreeDouble()
        val root = tree.element("body")
        val logs = HashMap<String, List<String>>()
        val s = Scenario(tree, root,
            html = { html(root.children) },
            items = { (root.children.first { it is DElement && it.tag == "ul" } as DElement).children.filterIsInstance<DElement>() },
            attribute = { n, a -> (n as DElement).attributes[a] },
            text = { engine.getTemplate("page.vtl").merge(it) },
            render = { c: Context, out: Appendable -> engine.getTemplate("page.vtl").merge(c, out) },
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
        val engine = VelocityEngine(ScriptingCompiler(), config)
            .apply { addResourceLoader("page", ResourceLoader { name -> if (name == "page.vtl") content(page) else null }) }
        val (s, tree) = scenario(engine)
        s.run(); check(tree.asDynamicLogs)
    }

    @Test fun compiled() {
        val engine = VelocityEngine(NoCompiler, config).apply { addResourceLoader("stock", StockResourceLoader(VelocityTemplates::resolve)) }
        val (s, tree) = scenario(engine)
        s.run(); check(tree.asDynamicLogs)
    }

    private object NoCompiler : Compiler {
        override fun compile(expr: Expr): Evaluable = throw IllegalStateException("compiled pipeline: unexpected expression ${expr.source}")
    }
}

/** `$state.users` is one reference; values are escaped by the engine on text output, never on the sink. */
val config = Config(informalNavigation = true, referenceInsertionEventClasses = listOf("org.apache.velocity.engine.sink.DynamicEscaper"))
