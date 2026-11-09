package com.republicate.kroom.webapp.authoring.git

import org.eclipse.jgit.api.Git
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The git store against the Versioned contract the memory store pins, plus what only git can get wrong: the
 * repository hidden from the content, a block's past following it across a move, content found at creation.
 */
class GitResourceStoreTest {

    private val root: Path = Files.createTempDirectory("kroom-git-store")
    private val store = GitResourceStore(root, domain = "test.local")

    @Test
    fun `every write is a commit by the block's author, and the same body again is none`() {
        val first = store.write("pages/a/content.md", "un", mapOf("author" to "admin"))
        store.write("pages/a/content.md", "deux", mapOf("author" to "nestor"))
        store.write("pages/a/content.md", "deux", mapOf("author" to "nestor"))
        val log = store.log("pages/a/content.md")
        assertEquals(listOf("nestor", "admin"), log.map { it.author })
        assertEquals("un", store.read("pages/a/content.md", log.last().rev)!!.body)
        assertEquals("un", store.read("pages/a/content.md", log.last().rev.take(7))!!.body, "an abbreviated id names it too")
        assertEquals("deux", store.read("pages/a/content.md")!!.body)
        assertEquals(first.rev, store.read("pages/a/content.md", log.last().rev)!!.rev, "a stored revision is the block as it was")
        assertEquals("admin@test.local", Git.open(root.toFile()).log().call().last().authorIdent.emailAddress)
    }

    @Test
    fun `the journal lists every change, newest first, with the path it touched`() {
        store.write("a.md", "un", mapOf("author" to "admin"))
        store.write("b.md", "deux", mapOf("author" to "nestor"))
        store.delete("a.md")
        store.write("c.md", "trois", mapOf("author" to "admin"))
        store.move("c.md", "d.md")
        assertEquals(listOf("d.md", "c.md", "a.md", "b.md", "a.md"), store.log().map { it.path })
        assertEquals("moved from c.md", store.log().first().message)
        assertEquals("a.md (deleted)", store.log()[2].message)
        assertEquals(2, store.log(limit = 2).size)
    }

    @Test
    fun `a moved block keeps its past, and the move is in its history`() {
        store.write("pages/a/content.md", "un", mapOf("author" to "admin"))
        val first = store.log("pages/a/content.md").single().rev
        store.write("pages/a/content.md", "deux", mapOf("author" to "admin"))
        store.move("pages/a/content.md", "pages/b/content.md")
        assertNull(store.read("pages/a/content.md"))
        val log = store.log("pages/b/content.md")
        assertEquals(3, log.size)
        assertEquals("moved from pages/a/content.md", log.first().message)
        assertEquals(listOf("pages/b/content.md", "pages/a/content.md", "pages/a/content.md"), log.map { it.path }, "each revision under the name it bore")
        assertEquals("un", store.read("pages/b/content.md", first)!!.body, "an old revision answers under the new path")
        assertEquals(1, store.moveAll("pages", "archive/pages"))
        assertEquals("un", store.read("archive/pages/b/content.md", first)!!.body, "and again after the tree moved")
    }

    @Test
    fun `the repository is hidden from the content, and content found at creation is the first commit`() {
        val existing = Files.createTempDirectory("kroom-git-existing")
        existing.resolve("pages/old/content.md").createParentDirectories().writeText("%%@ author legacy\n\nvieux")
        val found = GitResourceStore(existing)
        assertEquals(listOf("pages/old/content.md"), found.list())
        assertEquals("initial content", found.log().single().message)
        assertEquals("legacy", found.read("pages/old/content.md")!!.author)
        assertTrue(Files.isDirectory(existing.resolve(".git")))

        assertFailsWith<IllegalArgumentException> { found.write(".git/config", "x", emptyMap()) }
        assertNull(found.read(".git/config"))
        assertNull(found.read("pages/old/content.md", "not-a-rev"))
        assertNull(found.read("pages/old/content.md", "deadbeef"))
        assertEquals(emptyList(), GitResourceStore(Files.createTempDirectory("kroom-git-empty")).log(), "an empty repository has no history")
    }
}
