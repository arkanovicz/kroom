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

    @Test
    fun `a block moves, a tree of them too, and a taken place is refused`() {
        store.write("pages/a/content.md", "un", mapOf("author" to "admin"))
        store.write("pages/a/east.md", "deux", emptyMap())
        store.write("pages/b/content.md", "trois", emptyMap())
        assertEquals(false, store.move("pages/nowhere.md", "pages/x.md"))
        assertEquals(2, store.moveAll("pages/a", "pages/c/a"))
        assertNull(store.read("pages/a/content.md"))
        assertEquals("un", store.read("pages/c/a/content.md")!!.body)
        assertEquals("admin", store.read("pages/c/a/content.md")!!.author)
        assertEquals("pages/c/a/content.md", store.read("pages/c/a/content.md")!!.path)
        kotlin.test.assertFailsWith<IllegalArgumentException> { store.move("pages/c/a/content.md", "pages/b/content.md") }
        kotlin.test.assertFailsWith<IllegalArgumentException> { store.moveAll("pages/c/a", "pages/b") }
        assertEquals("trois", store.read("pages/b/content.md")!!.body, "a refused move changes nothing")
        assertEquals("un", store.read("pages/c/a/content.md")!!.body)
    }

    @Test
    fun `a moved block keeps its past, and the move is in its history`() {
        val versioned = VersionedMemoryResourceStore()
        versioned.write("pages/a/content.md", "un", mapOf("author" to "admin"))
        val first = versioned.log("pages/a/content.md").single().rev
        versioned.write("pages/a/content.md", "deux", mapOf("author" to "admin"))
        versioned.move("pages/a/content.md", "pages/b/content.md")
        assertEquals(emptyList(), versioned.log("pages/a/content.md"), "nothing is left behind")
        val log = versioned.log("pages/b/content.md")
        assertEquals(3, log.size)
        assertEquals("moved from pages/a/content.md", log.first().message)
        assertEquals("un", versioned.read("pages/b/content.md", first)!!.body, "an old revision answers under the new path")
    }

    @Test
    fun `a file store moves the file`() {
        val root = java.nio.file.Files.createTempDirectory("kroom-store")
        try {
            val files = FileResourceStore(root)
            files.write("pages/a/content.md", "un", mapOf("author" to "admin"))
            assertEquals(true, files.move("pages/a/content.md", "pages/b/deep/content.md"))
            assertNull(files.read("pages/a/content.md"))
            assertEquals("un", files.read("pages/b/deep/content.md")!!.body)
            assertEquals(listOf("pages/b/deep/content.md"), files.list("pages/"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
