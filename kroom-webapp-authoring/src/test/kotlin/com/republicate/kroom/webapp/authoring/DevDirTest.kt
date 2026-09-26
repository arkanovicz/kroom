package com.republicate.kroom.webapp.authoring

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * In dev, one source tree serves everything the classpath would: an edit to a layout or a stylesheet shows
 * on the next request, with no restart and nothing kept by the browser.
 */
class DevDirTest {

    @Test
    fun `layouts and static files are both read live from the dev resources directory`() = testApplication {
        val resources = Files.createTempDirectory("kroom-dev").toFile()
        val page = resources.resolve("templates/pages/hello.html").apply { parentFile.mkdirs(); writeText("first page") }
        val css = resources.resolve("static/css/site.css").apply { parentFile.mkdirs(); writeText("body { color: red }") }

        application {
            installContentSite {
                devDir = resources
            }
        }

        assertContains(client.get("/hello").bodyAsText(), "first page")
        client.get("/css/site.css").let {
            assertEquals("body { color: red }", it.bodyAsText())
            assertEquals("no-cache", it.headers[HttpHeaders.CacheControl])
        }

        page.writeText("second page")
        css.writeText("body { color: blue }")

        assertContains(client.get("/hello").bodyAsText(), "second page")
        assertEquals("body { color: blue }", client.get("/css/site.css").bodyAsText())

        // what the dev tree lacks still comes from the classpath, uncached all the same
        assertEquals("no-cache", client.get("/js/authoring.js").headers[HttpHeaders.CacheControl])
        resources.deleteRecursively()
    }
}
