package com.republicate.kroom.spike

import org.apache.velocity.engine.Compiler
import org.apache.velocity.engine.Config
import org.apache.velocity.engine.Evaluable
import org.apache.velocity.engine.Expr
import org.apache.velocity.engine.VelocityEngine
import org.apache.velocity.engine.dom.JsTree
import org.apache.velocity.engine.resource.StockResourceLoader
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.asList
import velocity.templates.VelocityTemplates
import kotlin.test.Test

/** The compiled class merged through the engine's [JsTree] into jsdom's DOM: what the browser does. */
class SinkJsTest {
    private object NoCompiler : Compiler {
        override fun compile(expr: Expr): Evaluable = throw IllegalStateException("compiled pipeline: unexpected expression ${expr.source}")
    }

    @Test fun compiledOnJsdom() {
        val jsdom = js("require('jsdom')")
        val dom = js("new jsdom.JSDOM('<!doctype html><html><body></body></html>')")
        // Kotlin/JS casts to external DOM types are `instanceof` checks against globals node does not have: lend it jsdom's
        js("globalThis.Node = dom.window.Node; globalThis.Element = dom.window.Element; globalThis.Text = dom.window.Text; globalThis.Document = dom.window.Document; globalThis.HTMLElement = dom.window.HTMLElement")
        val document = dom.window.document.unsafeCast<Document>()   // another realm: no `as`
        val body = document.body!!
        val config = Config(informalNavigation = true, referenceInsertionEventClasses = listOf("org.apache.velocity.engine.sink.DynamicEscaper"))
        val engine = VelocityEngine(NoCompiler, config).apply { addResourceLoader("stock", StockResourceLoader(VelocityTemplates::resolve)) }
        Scenario<Node>(JsTree(document), body,
            html = { body.innerHTML },
            items = { (body.querySelector("ul#users") as Element).children.asList() },
            attribute = { n, a -> (n as Element).getAttribute(a) },
            text = { engine.getTemplate("page.vtl").merge(it) },
            render = { c, out -> engine.getTemplate("page.vtl").merge(c, out) },
        ).run()
    }
}
