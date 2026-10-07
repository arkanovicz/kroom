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

    /**
     * site2026: a region partial edited on disk showed only after a restart. A literal `#parse("x")` is re-read
     * when its file changes; a computed `#parse($name)` — which is how every region is reached (`#region`) — kept
     * the template it first resolved. Velocity's to fix: a resolved name must still go through the loader's check.
     */
    @Test
    fun `a parsed partial is re-read when its file changes, by a literal or a computed name`() = testApplication {
        val resources = Files.createTempDirectory("kroom-dev").toFile()
        val partial = resources.resolve("templates/inc/foot.html").apply { parentFile.mkdirs(); writeText("first foot") }
        resources.resolve("templates/pages/literal.html").apply { parentFile.mkdirs(); writeText("#parse(\"inc/foot.html\")") }
        resources.resolve("templates/pages/computed.html").writeText("#set(\$name = \"inc/foot.html\")#parse(\$name)")
        resources.resolve("templates/pages/laid.html").writeText("#define(\$content)laid#end#layout()")
        val region = resources.resolve("templates/themes/t/regions/footer.html").apply { parentFile.mkdirs(); writeText("<footer>first region</footer>") }
        application {
            installContentSite {
                devDir = resources
                plugins += object : Skin { override val id = "t"; override val stylesheets = emptyList<String>() }
            }
        }
        assertContains(client.get("/literal").bodyAsText(), "first foot")
        assertContains(client.get("/computed").bodyAsText(), "first foot")
        assertContains(client.get("/laid").bodyAsText(), "<footer>first region</footer>")

        partial.writeText("second foot")
        region.writeText("<footer>second region</footer>")
        assertContains(client.get("/literal").bodyAsText(), "second foot", message = "a literal #parse")
        assertContains(client.get("/computed").bodyAsText(), "second foot", message = "a computed #parse")
        assertContains(client.get("/laid").bodyAsText(), "<footer>second region</footer>", message = "a theme's region, through #region")
        resources.deleteRecursively()
    }
}
