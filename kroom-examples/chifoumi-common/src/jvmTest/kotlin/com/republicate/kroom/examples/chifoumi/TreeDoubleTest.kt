package com.republicate.kroom.examples.chifoumi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TreeDoubleTest {
    private val tree = TreeDouble()

    private fun list(): Triple<DElement, DElement, DElement> {
        val ul = tree.element("ul")
        val a = tree.element("li")
        tree.setAttribute(a, "class", "a")
        tree.insertBefore(a, tree.text("x"), null)
        tree.insertBefore(ul, a, null)
        val b = tree.element("li")
        tree.insertBefore(b, tree.text("y & z"), null)
        tree.insertBefore(ul, b, null)
        return Triple(ul, a, b)
    }

    @Test
    fun build() {
        val (ul, _, _) = list()
        assertEquals("""<ul><li class="a">x</li><li>y &amp; z</li></ul>""", html(ul))
        assertEquals("xy & z", ul.textContent())
        assertEquals(listOf("li", "#text", "li", "#text"), ul.descendants().map { (it as? DElement)?.tag ?: "#text" }.toList())
        assertEquals(
            listOf(
                """element(#1, "ul")""",
                """element(#2, "li")""",
                """setAttribute(#2, "class", "a")""",
                """text(#3, "x")""",
                """insertBefore(#2, #3, null)""",
                """insertBefore(#1, #2, null)""",
                """element(#4, "li")""",
                """text(#5, "y & z")""",
                """insertBefore(#4, #5, null)""",
                """insertBefore(#1, #4, null)""",
            ),
            tree.log,
        )
    }

    @Test
    fun writes() {
        val (ul, a, _) = list()
        tree.clearLog()
        val x = a.children.single()
        assertEquals("x", tree.textOf(x))
        tree.setText(x, "AWAY")
        tree.removeAttribute(a, "class")
        assertNull(tree.getAttribute(a, "class"))
        assertEquals(listOf("""setText(#3, "AWAY")""", """removeAttribute(#2, "class")"""), tree.log)
        assertEquals("<ul><li>AWAY</li><li>y &amp; z</li></ul>", html(ul))
        assertFailsWith<IllegalArgumentException> { tree.textOf(a) }
        assertFailsWith<IllegalArgumentException> { tree.setText(ul, "no") }
    }

    @Test
    fun move() {
        val (ul, a, b) = list()
        tree.clearLog()
        tree.insertBefore(ul, b, a)
        assertSame(b, ul.children[0])
        assertSame(a, ul.children[1])
        assertSame(a, tree.nextSibling(b))
        assertNull(tree.nextSibling(a))
        assertEquals("""<ul><li>y &amp; z</li><li class="a">x</li></ul>""", html(ul))

        val div = tree.element("div")
        tree.insertBefore(div, a, null)
        assertSame(div, tree.parent(a))
        assertEquals(listOf<DNode>(b), ul.children)
        assertEquals(listOf("insertBefore(#1, #4, #2)", """element(#6, "div")""", "insertBefore(#6, #2, null)"), tree.log)

        assertFailsWith<IllegalArgumentException> { tree.insertBefore(ul, a, b.children.single()) }
        assertFailsWith<IllegalArgumentException> { tree.insertBefore(a, div, null) }
    }

    @Test
    fun remove() {
        val (ul, a, b) = list()
        tree.clearLog()
        tree.remove(a)
        assertNull(tree.parent(a))
        assertEquals(listOf<DNode>(b), ul.children)
        assertEquals("<ul><li>y &amp; z</li></ul>", html(ul))
        assertEquals(listOf("remove(#2)"), tree.log)
        // a removed node stays whole and can come back
        assertEquals("""<li class="a">x</li>""", html(a))
        tree.insertBefore(ul, a, null)
        assertTrue(ul.children.last() === a)
    }

    @Test
    fun serialization() {
        val img = tree.element("img")
        tree.setAttribute(img, "alt", "\"<a&b>\"")
        val br = tree.element("br")
        assertEquals("""<img alt="&quot;&lt;a&amp;b&gt;&quot;"><br>""", html(listOf(img, br)))
    }
}
