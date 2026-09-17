package com.republicate.kroom

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pinned because both directions must stay each other's inverse: the router matches a URL against a
 * template and binds its values, then a block's path is expanded with those same values. A drift between
 * the two would serve one page and edit another.
 */
class PathTemplateTest {

    @Test
    fun `a placeholder segment matches one segment and binds it`() {
        val t = PathTemplate("pages/club/_code_/club.html")
        assertEquals(listOf("code"), t.params)
        assertEquals("pages/club/{code}/club.html", t.pattern)
        assertEquals(mapOf("code" to "13Ma"), t.match("pages/club/13Ma/club.html"))
        assertEquals("pages/club/13Ma/club.html", t.expand(mapOf("code" to "13Ma")))
    }

    @Test
    fun `a placeholder inside a file name works the same`() {
        val t = PathTemplate("pages/club/_code_.md")
        assertEquals(mapOf("code" to "13Ma"), t.match("pages/club/13Ma.md"))
        assertEquals("pages/club/13Ma.md", t.expand(mapOf("code" to "13Ma")))
    }

    @Test
    fun `match and expand are inverses on several placeholders`() {
        val t = PathTemplate("pages/_lang_/club/_code_/team/_n_.md")
        val path = "pages/fr/club/13Ma/team/2.md"
        assertEquals(path, t.expand(t.match(path)!!))
        assertEquals(listOf("lang", "code", "n"), t.params)
    }

    @Test
    fun `a concrete path is its own match`() {
        val t = PathTemplate("pages/legal/terms.html")
        assertTrue(t.isConcrete)
        assertEquals(emptyMap(), t.match("pages/legal/terms.html"))
        assertEquals("pages/legal/terms.html", t.expand(emptyMap()))
    }

    @Test
    fun `a placeholder never matches across a slash, nor a wrong depth`() {
        val t = PathTemplate("pages/club/_code_/club.html")
        assertNull(t.match("pages/club/13Ma/sub/club.html"))
        assertNull(t.match("pages/club/13Ma/other.html"))
        assertNull(t.match("pages/club/club.html"))
    }

    @Test
    fun `a missing value is an error, not a guess`() {
        val t = PathTemplate("pages/club/_code_/club.html")
        val failure = assertFailsWith<IllegalArgumentException> { t.expand(emptyMap()) }
        assertTrue(failure.message!!.contains("code"), failure.message!!)
        assertFailsWith<IllegalArgumentException> { t.expand(mapOf("code" to "a/b")) }
    }

    @Test
    fun `an ordinary underscored name is not a placeholder`() {
        val t = PathTemplate("pages/my_file.md")
        assertTrue(t.isConcrete)
        assertEquals(emptyMap(), t.match("pages/my_file.md"))
    }
}
