package com.republicate.kroom.spike

import java.util.IdentityHashMap

/** A DOM subtree in memory: what a sink builds on the JVM, inspectable and serializable. */
sealed class DNode {
    var parent: DElement? = null
        internal set
}

class DElement(val tag: String) : DNode() {
    val attributes = LinkedHashMap<String, String>()
    val children: MutableList<DNode> = mutableListOf()
}

class DText(var data: String) : DNode()

/**
 * The [Tree] over [DNode]s, logging every write so a test can pin how many mutations a frame costs,
 * not only the tree it leaves.
 */
class TreeDouble : Tree<DNode> {
    val log: MutableList<String> = mutableListOf()
    private val numbers = IdentityHashMap<DNode, Int>()

    fun clearLog() = log.clear()

    // a node built outside this tree gets its number when first seen
    private fun num(node: DNode?) = if (node == null) "null" else "#" + numbers.getOrPut(node) { numbers.size + 1 }

    override fun element(tag: String) = DElement(tag).also { log += "element(${num(it)}, ${quote(tag)})" }

    override fun text(data: String) = DText(data).also { log += "text(${num(it)}, ${quote(data)})" }

    override fun textOf(text: DNode) = asText(text).data

    override fun setText(text: DNode, data: String) {
        asText(text).data = data
        log += "setText(${num(text)}, ${quote(data)})"
    }

    override fun getAttribute(element: DNode, name: String) = asElement(element).attributes[name]

    override fun setAttribute(element: DNode, name: String, value: String) {
        asElement(element).attributes[name] = value
        log += "setAttribute(${num(element)}, ${quote(name)}, ${quote(value)})"
    }

    override fun removeAttribute(element: DNode, name: String) {
        asElement(element).attributes.remove(name)
        log += "removeAttribute(${num(element)}, ${quote(name)})"
    }

    override fun insertBefore(parent: DNode, child: DNode, before: DNode?) {
        val p = asElement(parent)
        require(before == null || before.parent === p) { "${num(before)} is not a child of ${num(parent)}" }
        var ancestor: DNode? = p
        while (ancestor != null) {
            require(ancestor !== child) { "${num(child)} would contain itself" }
            ancestor = ancestor.parent
        }
        // as in the DOM: inserting a node before itself keeps it in place
        val ref = if (before === child) nextSibling(child) else before
        detach(child)
        val at = if (ref == null) p.children.size else p.children.indexOfFirst { it === ref }
        p.children.add(at, child)
        child.parent = p
        log += "insertBefore(${num(parent)}, ${num(child)}, ${num(before)})"
    }

    override fun remove(node: DNode) {
        detach(node)
        log += "remove(${num(node)})"
    }

    override fun parent(node: DNode): DNode? = node.parent

    override fun nextSibling(node: DNode): DNode? {
        val siblings = node.parent?.children ?: return null
        return siblings.getOrNull(siblings.indexOfFirst { it === node } + 1)
    }

    private fun detach(node: DNode) {
        node.parent?.children?.removeAll { it === node }
        node.parent = null
    }

    private fun asText(node: DNode) = node as? DText ?: throw IllegalArgumentException("${num(node)} is not a text")
    private fun asElement(node: DNode) = node as? DElement ?: throw IllegalArgumentException("${num(node)} is not an element")

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}

private val voidElements = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")

fun html(node: DNode): String = StringBuilder().also { serialize(node, it) }.toString()

fun html(nodes: List<DNode>): String = StringBuilder().also { sb -> nodes.forEach { serialize(it, sb) } }.toString()

private fun serialize(node: DNode, sb: StringBuilder) {
    when (node) {
        is DText -> sb.append(escapeText(node.data))
        is DElement -> {
            sb.append('<').append(node.tag)
            node.attributes.forEach { (name, value) -> sb.append(' ').append(name).append("=\"").append(escapeAttribute(value)).append('"') }
            sb.append('>')
            if (node.tag !in voidElements) {
                node.children.forEach { serialize(it, sb) }
                sb.append("</").append(node.tag).append('>')
            }
        }
    }
}

private fun escapeText(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
private fun escapeAttribute(s: String) = escapeText(s).replace("\"", "&quot;")

/** Depth-first, document order, without the receiver. */
fun DElement.descendants(): Sequence<DNode> = sequence {
    for (child in children) {
        yield(child)
        if (child is DElement) yieldAll(child.descendants())
    }
}

fun DNode.textContent(): String = when (this) {
    is DText -> data
    is DElement -> descendants().filterIsInstance<DText>().joinToString("") { it.data }
}
