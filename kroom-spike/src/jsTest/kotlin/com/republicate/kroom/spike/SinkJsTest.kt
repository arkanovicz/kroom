package com.republicate.kroom.spike

import org.apache.velocity.engine.Compiler
import org.apache.velocity.engine.Config
import org.apache.velocity.engine.Evaluable
import org.apache.velocity.engine.Expr
import org.apache.velocity.engine.VelocityEngine
import org.apache.velocity.engine.resource.StockResourceLoader
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.Text
import org.w3c.dom.asList
import velocity.templates.VelocityTemplates
import kotlin.test.Test

/** The sink over jsdom's DOM: the same operations `org.w3c.dom` offers in a browser. */
class JsTree(private val document: Document) : Tree<Node> {
    override fun element(tag: String): Node = document.createElement(tag)
    override fun text(data: String): Node = document.createTextNode(data)
    override fun textOf(text: Node): String = (text as Text).data
    override fun setText(text: Node, data: String) { (text as Text).data = data }
    override fun getAttribute(element: Node, name: String): String? = (element as Element).getAttribute(name)
    override fun setAttribute(element: Node, name: String, value: String) { (element as Element).setAttribute(name, value) }
    override fun removeAttribute(element: Node, name: String) { (element as Element).removeAttribute(name) }
    override fun insertBefore(parent: Node, child: Node, before: Node?) { parent.insertBefore(child, before) }
    override fun remove(node: Node) { node.parentNode?.removeChild(node) }
    override fun parent(node: Node): Node? = node.parentNode
    override fun nextSibling(node: Node): Node? = node.nextSibling
}

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
        val tree = JsTree(document)
        val engine = VelocityEngine(NoCompiler, Config(informalNavigation = true)).apply { addResourceLoader("stock", StockResourceLoader(VelocityTemplates::resolve)) }
        Scenario<Node>(engine, tree, body,
            html = { body.innerHTML },
            items = { (body.querySelector("ul#users") as Element).children.asList() },
            attribute = { n, a -> (n as Element).getAttribute(a) },
            text = { engine.getTemplate("page.vtl").merge(it) },
            render = { c, out -> engine.getTemplate("page.vtl").merge(c, out) },
        ).run()
    }
}
