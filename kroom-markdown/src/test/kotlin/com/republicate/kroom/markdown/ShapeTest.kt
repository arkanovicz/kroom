package com.republicate.kroom.markdown

import org.apache.velocity.engine.ResourceLoader
import org.apache.velocity.engine.ResourceNotFoundException
import org.apache.velocity.engine.VelocityContext
import org.apache.velocity.engine.VelocityEngine
import org.apache.velocity.engine.jvm.ScriptingCompiler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The editor's completion: what a block may write after `$` and each `.`, one level per call. */
class ShapeTest {

    class Membre(val nom: String, val prenom: String, val niveau: Int?) {
        fun salut(formule: String, ponctuation: String = "!") = "$formule $prenom$ponctuation"
        fun oublier() {}
    }

    class Club(val nom: String, private val m: List<Membre>) {
        fun membres(): List<Membre> = m
        val bureau: Map<String, Membre> get() = m.associateBy { it.nom }
    }

    data class Lieu(val ville: String)

    private val club = Club("Les Vagabonds", listOf(Membre("Lovelace", "Ada", 3)))
    private val renderer = MarkdownRenderer()

    private fun shape(vararg arguments: Pair<String, Any?>) = Shape(renderer.navigation, mapOf(*arguments))

    @Test
    fun `the first level is what the block sees, each named by what lies behind it`() {
        val level = shape("club" to club, "titre" to "Bienvenue", "membres" to club.membres(), "lieu" to Lieu("Paris")).unfold(emptyList())
        assertEquals(mapOf("club" to emptyMap<String, Any>(), "titre" to "String", "membres" to listOf(emptyMap<String, Any>()),
            "lieu" to emptyMap<String, Any>()), level)
    }

    @Test
    fun `each dot unfolds one level of the declared types`() {
        val shape = shape("club" to club)
        assertEquals(mapOf("nom" to "String", "membres()" to listOf(emptyMap<String, Any>()), "bureau" to emptyMap<String, Any>()),
            shape.unfold(listOf("club")))
        val membre = shape.unfold(listOf("club", "membres()", "[]"))!!
        assertEquals("String", membre["prenom"])
        assertEquals("Int", membre["niveau"])
        assertEquals("String", membre["salut(formule)"], "a call spells its required parameters only")
        assertFalse("oublier()" in membre, "what returns nothing prints nothing")
        assertFalse(membre.keys.any { it.startsWith("get") || it.startsWith("toString") || it == "class" }, membre.keys.toString())
    }

    @Test
    fun `a map navigates by key, and its keys come from a value or from nowhere`() {
        val shape = shape("club" to club, "options" to mapOf("couleur" to "vert", "lieu" to Lieu("Paris")))
        assertEquals(mapOf("*" to emptyMap<String, Any>()), shape.unfold(listOf("club", "bureau")))
        assertEquals("String", shape.unfold(listOf("club", "bureau", "Lovelace"))!!["prenom"])
        assertEquals(mapOf("couleur" to "String", "lieu" to emptyMap<String, Any>()), shape.unfold(listOf("options")))
        assertEquals(mapOf("ville" to "String"), shape.unfold(listOf("options", "lieu")), "a data class's plumbing is not offered")
    }

    @Test
    fun `a path that leads nowhere answers nothing`() {
        val shape = shape("club" to club)
        assertNull(shape.unfold(listOf("absent")))
        assertNull(shape.unfold(listOf("club", "nope")))
        assertNull(shape.unfold(listOf("club", "nom", "[]")))
    }

    /** The probe an editor sets in a page render: only the asked block answers, with its arguments and tools. */
    @Test
    fun `a page render fills the shape of the asked block`() {
        val pages = ResourceLoader { name -> javaClass.classLoader.getResource(name)?.readText() ?: throw ResourceNotFoundException(name) }
        val engine = VelocityEngine(compiler = ScriptingCompiler(), loader = pages)
        engine.addMacro("markdown", MarkdownMacro(MarkdownConfig(loader = { throw ResourceNotFoundException(it) })))
        val asked = mutableMapOf<String, Any?>("pages/club/13Ma/description.md" to null)
        engine.mergeTemplate("pages/club/_code_.html", VelocityContext(mutableMapOf(
            "code" to "13Ma", "club" to club, MarkdownMacro.SHAPES to asked)))
        @Suppress("UNCHECKED_CAST")
        val unfold = assertNotNull(asked["pages/club/13Ma/description.md"] as? (List<String>) -> Map<String, Any>?)
        assertEquals(setOf("club", "code"), unfold(emptyList())!!.keys)
        assertTrue("membres()" in unfold(listOf("club"))!!)
        assertEquals(1, asked.size, "a block nobody asked about records nothing")
    }
}
