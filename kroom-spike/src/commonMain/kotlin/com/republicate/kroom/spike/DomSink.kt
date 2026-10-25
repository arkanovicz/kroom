package com.republicate.kroom.spike

import com.republicate.kroom.html.Position
import com.republicate.kroom.html.Segment
import com.republicate.kroom.html.Segmentizer

/**
 * The Binding, built by the merge itself. Velocity renders through this Appendable; the insertion handler
 * says when a value begins ([expect]), the block macros say where a block or an item begins and ends. The
 * first [pass] builds the tree under the root; every later pass walks the same record in the same order and
 * touches only what differs: a value's text node, a dynamic attribute, an item added, removed or moved.
 *
 * Values never meet the HTML reader: they are data, placed where the reader says the output stands (text,
 * or an attribute's value), so they need no escaping. Static text does meet it, decoded.
 */
class DomSink<N : Any>(private val tree: Tree<N>, root: N) : Appendable {

    /** One rendering of a block body (or the whole page): what it created, in order, for the next pass to walk. */
    private class Item<N : Any> {
        val sites = ArrayList<N>()       // text nodes holding a value
        val elements = ArrayList<N>()    // elements, in opening order
        val blocks = ArrayList<Block<N>>()
        val topNodes = ArrayList<N>()    // nodes placed directly under the block's parent: the item's range
        var site = 0; var element = 0; var block = 0
        var building = true
        var depth = 0                    // open elements when the item began: deeper ones are its own
        fun reset(building: Boolean) { site = 0; element = 0; block = 0; this.building = building }
    }

    /** A `#@each` or `#@when`: its items by key, between two empty text nodes. */
    private class Block<N : Any>(val parent: N, val start: N?) {
        var end: N? = null
        var items = LinkedHashMap<String, Item<N>>()
        val visited = LinkedHashMap<String, Item<N>>()
        var lastNode: N? = start     // the last node placed at the block's level in this pass
    }

    private val root = Block(root, null).also { it.items[""] = Item() }
    private val blocks = ArrayDeque<Block<N>>()
    private val items = ArrayDeque<Item<N>>()
    private val stack = ArrayDeque<N>()          // open elements
    private val reader = Segmentizer()
    private val pending = StringBuilder()        // static text not yet a node
    private var expecting = false                // the next append is a value

    // the tag being read
    private var tag: N? = null
    private var attrs = LinkedHashMap<String, StringBuilder>()
    private var attr: String? = null
    private var dynamicTag = false

    private val item get() = items.last()
    private val block get() = blocks.last()

    /** One merge of the page: the first builds, the next ones update. */
    fun <T> pass(render: () -> T): T {
        val rootItem = root.items[""]!!
        rootItem.reset(building = rootItem.elements.isEmpty() && rootItem.sites.isEmpty() && rootItem.topNodes.isEmpty())
        root.lastNode = null
        blocks.addLast(root); items.addLast(rootItem)
        val result = render()
        flush()
        check(stack.isEmpty()) { "unclosed elements at the end of the page" }
        items.removeLast(); blocks.removeLast()
        return result
    }

    /** The insertion handler: what the engine appends next is the value of [reference]. */
    fun expect(reference: String) { expecting = true }

    // --- Appendable: the merge's output ---------------------------------------------------------------

    override fun append(value: Char): Appendable = append(value.toString())
    override fun append(value: CharSequence?, startIndex: Int, endIndex: Int): Appendable = append(value?.subSequence(startIndex, endIndex))
    override fun append(value: CharSequence?): Appendable {
        val text = value?.toString() ?: "null"
        if (expecting) { expecting = false; value(text) } else for (s in reader.segment(text)) static(s, text)
        return this
    }

    private fun value(text: String) = when (val p = reader.position) {
        Position.Text -> { flush(); site(text) }
        is Position.Attr -> attribute(p.name, text, dynamic = true)
        else -> error("a value in a $p position has no site")
    }

    private fun static(s: Segment, text: String) {
        val bytes = text.substring(s.range)
        when (s) {
            is Segment.Text -> pending.append(decode(bytes))
            is Segment.Open -> { flush(); open(s.tag) }
            is Segment.Attr -> attribute(s.name, decode(bytes), dynamic = false)
            is Segment.End -> end(selfClosing = bytes == "/>" || s.tag in VOID)
            is Segment.Close -> { flush(); check(stack.removeLast() != null) }
            is Segment.Other -> {}
        }
    }

    // --- building and updating ----------------------------------------------------------------------

    private fun flush() {
        if (pending.isEmpty()) return
        if (item.building) place(tree.text(pending.toString()))
        pending.setLength(0)
    }

    /** Insert under the item's own open element, or at the item's place in its block. */
    private fun place(node: N) {
        if (stack.size > item.depth) { tree.insertBefore(stack.last(), node, null); return }
        tree.insertBefore(block.parent, node, block.lastNode?.let { tree.nextSibling(it) })
        item.topNodes += node
        block.lastNode = node
    }

    private fun site(text: String) {
        if (item.building) { item.sites += tree.text(text).also { place(it) } }
        else item.sites[item.site++].let { if (tree.textOf(it) != text) tree.setText(it, text) }
    }

    private fun open(name: String) {
        tag = if (item.building) tree.element(name) else item.elements[item.element]
        item.element++
        attrs = LinkedHashMap(); attr = null; dynamicTag = false
    }

    private fun attribute(name: String, text: String, dynamic: Boolean) {
        attrs.getOrPut(name) { StringBuilder() }.append(text)
        if (dynamic) dynamicTag = true
    }

    private fun end(selfClosing: Boolean) {
        val el = tag ?: error("tag end without a tag")
        if (item.building) {
            for ((n, v) in attrs) tree.setAttribute(el, n, v.toString())
            place(el)
            item.elements += el
        } else if (dynamicTag) {
            for ((n, v) in attrs) if (tree.getAttribute(el, n) != v.toString()) tree.setAttribute(el, n, v.toString())
        }
        if (!selfClosing) stack.addLast(el)
        tag = null
    }

    // --- blocks: the macros' hooks -------------------------------------------------------------------

    fun enterBlock() {
        check(reader.position == Position.Text) { "a block inside a tag has no range" }
        flush()
        val b = if (item.building) Block(stack.lastOrNull() ?: block.parent, tree.text("")).also { place(it.start!!); item.blocks += it }
                else item.blocks[item.block]
        item.block++
        b.visited.clear(); b.lastNode = b.start
        blocks.addLast(b)
    }

    fun enterItem(key: String) {
        val b = block
        val existing = if (items.last().building) null else b.items[key]
        val it = existing ?: Item()
        if (existing != null) {
            existing.reset(building = false)
            val before = b.lastNode?.let { tree.nextSibling(it) }
            if (existing.topNodes.firstOrNull() !== before) for (n in existing.topNodes) tree.insertBefore(b.parent, n, before)
            existing.topNodes.lastOrNull()?.let { n -> b.lastNode = n }
        } else it.reset(building = true)
        it.depth = stack.size
        b.visited[key] = it
        items.addLast(it)
    }

    fun exitItem() {
        flush()
        items.removeLast()
    }

    fun exitBlock() {
        flush()
        val b = blocks.removeLast()
        for ((k, it) in b.items) if (k !in b.visited) for (n in it.topNodes) tree.remove(n)
        b.items = LinkedHashMap(b.visited)
        val level = stack.size == item.depth           // the block sits at its enclosing item's level, not inside an element of its own
        if (b.end == null) { if (level) block.lastNode = b.lastNode; b.end = tree.text("").also { place(it) } }
        else if (level) block.lastNode = b.end
    }

    private companion object {
        val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")
        val ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")
        val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")

        fun decode(s: String): String = if ('&' !in s) s else ENTITY.replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") -> e.substring(2).toInt(16).toChar().toString()
                e.startsWith("#") -> e.substring(1).toInt().toChar().toString()
                else -> ENTITIES[e] ?: m.value
            }
        }
    }
}
