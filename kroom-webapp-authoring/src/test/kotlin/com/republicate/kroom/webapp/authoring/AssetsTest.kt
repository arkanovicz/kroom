package com.republicate.kroom.webapp.authoring

import com.republicate.kroom.webapp.core.installCore
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The editor is three files under this jar's `static/` plus one template at the classpath root, and no
 * route of authoring's own — core's static routes already serve any jar. So what a test can catch here is
 * the packaging: a file moved out of reach, or tags pointing where nothing is served.
 */
class AssetsTest {

    private val served = mapOf(
        "/js/authoring.js" to ContentType.Application.JavaScript,
        "/css/authoring.css" to ContentType.Text.CSS,
        "/lib/diff-match-patch/diff_match_patch.js" to ContentType.Application.JavaScript
    )

    @Test
    fun `the editor's files are served, with their type, by the core static routes`() = testApplication {
        application { installCore() }

        served.forEach { (path, expected) ->
            val response = client.get("$path?v=${AuthoringAssets.VERSION}")
            assertEquals(HttpStatusCode.OK, response.status, path)
            assertEquals(expected.contentType, response.contentType()?.contentType, path)
            assertEquals(expected.contentSubtype, response.contentType()?.contentSubtype, path)
            assertTrue(response.bodyAsText().isNotEmpty(), path)
        }
    }

    @Test
    fun `the tags a layout emits name the files that are served`() {
        val tags = AuthoringAssets().tags()
        served.keys.forEach { path -> assertTrue(tags.contains("$path?v="), "$path missing from $tags") }
        assertTrue("kroomAuthoring" !in tags, "no strings, no script for them")
    }

    @Test
    fun `the plugins' words reach the admin bar, safe inside a script`() {
        val tags = AuthoringAssets().stringTags("kroomAdmin", mapOf("probe.name" to "Sonde", "evil" to "</script><b>"))
        assertTrue("\"probe.name\":\"Sonde\"" in tags, tags)
        assertTrue("</script><b>" !in tags, tags)
    }

    @Test
    fun `a chosen language reaches both scripts ahead of them, auto emits nothing`() {
        assertTrue("language" !in AuthoringAssets().stringTags("kroomAdmin"), "auto is the scripts' own default")
        val tags = AuthoringAssets(language = "fr").tags()
        assertTrue(tags.indexOf("\"language\":\"fr\"") in 0 until tags.indexOf("/js/authoring.js"), tags)
        assertTrue("window.kroomAdmin" in AuthoringAssets(language = "fr").stringTags("kroomAdmin"))
    }

    @Test
    fun `the default wrapper sits where every engine shape looks for it`() {
        val wrapper = Thread.currentThread().contextClassLoader.getResourceAsStream("kroom/block_wrapper.html")
        assertNotNull(wrapper, "kroom/block_wrapper.html must sit at the classpath root")
        val source = wrapper.reader().readText()
        assertTrue(source.contains("\$authoring.canEdit(\$logged, \$path)"))
        assertTrue(source.contains("data-content=\"\$path\""))
        assertTrue(source.contains("\$html"))
    }
}
