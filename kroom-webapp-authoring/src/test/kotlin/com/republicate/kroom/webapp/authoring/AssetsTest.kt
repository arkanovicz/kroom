package com.republicate.kroom.webapp.authoring

import com.republicate.kroom.webapp.assets.KroomAssets
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
        val tags = AuthoringAssets.tags()
        served.keys.forEach { path -> assertTrue(tags.contains("$path?v="), "$path missing from $tags") }
    }

    @Test
    fun `layout helpers have a zero-arg method templates reach without kotlin-reflect`() {
        listOf("coreScripts", "domhelperScript", "apiScript", "storeScript", "sseScript")
            .forEach { KroomAssets::class.java.getMethod(it) }
        listOf("tags", "styleTags", "scriptTags").forEach { AuthoringAssets::class.java.getMethod(it) }
    }

    @Test
    fun `the default wrapper sits where every engine shape looks for it`() {
        val wrapper = Thread.currentThread().contextClassLoader.getResourceAsStream("kroom/block-wrapper.html")
        assertNotNull(wrapper, "kroom/block-wrapper.html must sit at the classpath root")
        val source = wrapper.reader().readText()
        assertTrue(source.contains("\$authoring.canEdit(\$logged, \$path)"))
        assertTrue(source.contains("data-content=\"\$path\""))
        assertTrue(source.contains("\$html"))
    }
}
