package com.republicate.kroom.webapp.authoring

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The store's contract, pinned where a mistake would be silent: the editor must never be shown the header,
 * a write must never drop meta it does not know about, and the stored source must stay exactly what the
 * renderer parses — one tree read and written through one object.
 */
class ResourceStoreTest {

    private val store = MemoryResourceStore()

    @Test
    fun `the editor sees the body, velocity sees header and body`() {
        store.write("club/description.md", "## Les Vagabonds\n\nUn club.", mapOf("author" to "admin", "updated" to "1757000000"))
        val block = store.read("club/description.md")!!
        assertEquals("## Les Vagabonds\n\nUn club.", block.body)
        assertEquals("admin", block.author)

        val source = store.load("club/description.md")
        assertEquals("%%@ author admin\n%%@ updated 1757000000\n\n## Les Vagabonds\n\nUn club.", source)
    }

    @Test
    fun `a write keeps header keys it was not given`() {
        store.write("a.md", "un", mapOf("author" to "admin", "edit" to "bureau-13Ma"))
        store.write("a.md", "deux", mapOf("author" to "nestor"))
        val block = store.read("a.md")!!
        assertEquals("deux", block.body)
        assertEquals("nestor", block.meta["author"])
        assertEquals("bureau-13Ma", block.meta["edit"])   // the application's own key survived the edit
    }

    @Test
    fun `rev identifies the body, so an edit made outside the editor is seen`() {
        val first = store.write("a.md", "un", mapOf("author" to "admin"))
        val sameBody = store.write("a.md", "un", mapOf("author" to "nestor"))
        assertEquals(first.rev, sameBody.rev)            // meta is not content
        assertNotEquals(first.rev, store.write("a.md", "deux", emptyMap()).rev)
    }

    @Test
    fun `an unwritten block reads null, and list walks the tree`() {
        assertNull(store.read("nowhere.md"))
        store.write("pages/club/13Ma/description.md", "un", emptyMap())
        store.write("pages/legal/terms.md", "deux", emptyMap())
        assertEquals(listOf("pages/club/13Ma/description.md"), store.list("pages/club/"))
        assertEquals(2, store.list().size)
    }

    @Test
    fun `a versioned store answers its own history and the site-wide journal`() {
        val versioned = VersionedMemoryResourceStore()
        val first = versioned.write("a.md", "un", mapOf("author" to "admin"))
        versioned.write("a.md", "deux", mapOf("author" to "nestor"))
        versioned.write("b.md", "trois", mapOf("author" to "admin"))

        assertEquals(listOf("nestor", "admin"), versioned.log("a.md").map { it.author })
        assertEquals(listOf("b.md", "a.md", "a.md"), versioned.log().map { it.path })   // journal, newest first
        assertEquals("un", versioned.read("a.md", first.rev)!!.body)                    // an undo reads, then writes
    }

    @Test
    fun `a file store round-trips and refuses to escape its root`() {
        val root = Files.createTempDirectory("kroom-store")
        val file = FileResourceStore(root)
        file.write("pages/club/13Ma/description.md", "## Titre", mapOf("author" to "admin"))
        assertEquals("## Titre", file.read("pages/club/13Ma/description.md")!!.body)
        assertContains(root.resolve("pages/club/13Ma/description.md").readText(), "%%@ author admin")
        assertEquals(listOf("pages/club/13Ma/description.md"), file.list())
        assertFailsWith<IllegalArgumentException> { file.read("../../etc/passwd") }
    }
}
