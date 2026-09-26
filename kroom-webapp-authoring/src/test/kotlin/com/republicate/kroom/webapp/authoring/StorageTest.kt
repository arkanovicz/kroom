package com.republicate.kroom.webapp.authoring

import com.republicate.kson.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The storage contract, run against both shipped implementations: they must be interchangeable. */
class StorageTest {

    private fun storages() = listOf(MemoryStorage(), FileStorage(Files.createTempDirectory("kroom-storage")))

    @Test
    fun `settings are per namespace, and null removes`() = storages().forEach { storage ->
        val seo = storage.settings("seo")
        seo["title"] = "Les Vagabonds"
        seo["suffix"] = " — FFG"
        assertEquals("Les Vagabonds", storage.settings("seo")["title"])
        assertNull(storage.settings("forms")["title"])
        seo["suffix"] = null
        assertEquals(mapOf("title" to "Les Vagabonds"), storage.settings("seo").all())
    }

    @Test
    fun `records list newest first, by the ids they were given`() = storages().forEach { storage ->
        val entries = storage.records("forms", "contact")
        val first = entries.add(Json.MutableObject().apply { set("name", "Alice") })
        val second = entries.add(Json.MutableObject().apply { set("name", "Bob") })
        assertEquals(listOf(second, first), entries.list().keys.toList())
        assertEquals("Alice", entries.get(first)!!.getString("name"))
        assertTrue(entries.delete(first))
        assertEquals(listOf(second), storage.records("forms", "contact").list().keys.toList())
    }

    @Test
    fun `a namespace is never a path`() = storages().forEach { storage ->
        assertFailsWith<IllegalArgumentException> { storage.settings("../etc") }
        assertFailsWith<IllegalArgumentException> { storage.records("forms", "a/b") }
    }
}
