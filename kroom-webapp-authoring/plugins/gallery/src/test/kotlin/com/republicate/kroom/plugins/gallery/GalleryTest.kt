package com.republicate.kroom.plugins.gallery

import com.republicate.kroom.webapp.authoring.FileStorage
import com.republicate.kroom.webapp.authoring.IdentityProvider
import com.republicate.kroom.webapp.authoring.Roles
import com.republicate.kroom.webapp.authoring.Storage
import com.republicate.kroom.webapp.authoring.installContentSite
import com.republicate.kroom.webapp.images.Images
import com.republicate.kroom.webapp.session.UserSession
import com.republicate.kson.Json
import io.ktor.client.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A gallery page born seeded, its pictures uploaded and arranged through the API, shown as a grid to anyone and
 * as an editable one to whoever may; renditions through Images when there is one, the originals otherwise.
 */
class GalleryTest {

    private val root = Files.createTempDirectory("kroom-gallery")
    private val storage: Storage = FileStorage(root)
    private val png: ByteArray = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")

    private fun ApplicationTestBuilder.site(gallery: Gallery = Gallery()) = application {
        installContentSite {
            storage = this@GalleryTest.storage
            sessionSecret = "test-secret"
            identity = IdentityProvider { mapOf("ed" to setOf(Roles.EDITOR), "au" to setOf(Roles.AUTHOR))[it.id].orEmpty() }
            plugins += gallery
        }
        routing { get("/as/{who}") { call.sessions.set(UserSession(call.parameters["who"]!!, "x", null, "t")); call.respondText("ok") } }
    }

    private suspend fun ApplicationTestBuilder.visitor(name: String? = null): HttpClient =
        createClient { install(HttpCookies); followRedirects = false }.also { if (name != null) it.get("/as/$name") }

    private suspend fun HttpClient.json(method: HttpMethod, url: String, body: String) = request(url) {
        this.method = method; contentType(ContentType.Application.Json); setBody(body)
    }

    /** A gallery page made by [editor], published; the gallery's id. */
    private suspend fun HttpClient.galleryPage(path: String): String {
        json(HttpMethod.Post, "/api/site/pages", """{"path":"$path","layout":"gallery"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        json(HttpMethod.Put, "/api/site/pages$path", """{"status":"published"}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        val seed = storage.content.read("pages$path/content.md")!!.body
        return Regex("""\${'$'}gallery\.show\("([a-z0-9]+)"\)""").matchEntire(seed)?.groupValues?.get(1) ?: error("not a gallery seed: $seed")
    }

    private suspend fun HttpClient.upload(name: String): String {
        val answer = post("/api/content/media?name=$name") { setBody(png) }
        assertEquals(HttpStatusCode.OK, answer.status, answer.bodyAsText())
        return (Json.parse(answer.bodyAsText()) as Json.Object).getString("name")!!
    }

    @Test
    fun `a gallery page is born with its gallery, filled through the API, shown to all and edited by some`() = testApplication {
        site()
        val editor = visitor("ed")
        val id = editor.galleryPage("/photos/plage")

        val empty = visitor().get("/photos/plage").bodyAsText()
        assertContains(empty, """<div class="kroom-gallery" data-gallery="$id" data-api="/api/gallery" data-media="/api/content/media" data-lang="en">""")
        assertContains(empty, "/js/gallery.js?v=", message = "the show's script rides on the page that shows a gallery")
        assertFalse(empty.contains("Sortable.js"), "a visitor drags nothing")

        val plage = editor.upload("plage.png")
        val port = editor.upload("port.png")
        val arranged = editor.json(HttpMethod.Put, "/api/gallery/$id", """{"items":[{"media":"$port","captions":{"en":"The harbour"}},{"media":"$plage","captions":{"en":"The beach","xx":"ignored"}}]}""")
        assertEquals(HttpStatusCode.OK, arranged.status, arranged.bodyAsText())
        val view = Json.parse(arranged.bodyAsText()) as Json.Object
        assertEquals(listOf(port, plage), view.getArray("items")!!.map { (it as Json.Object).getString("media") }, "in the editor's order")
        val first = view.getArray("items")!![0] as Json.Object
        assertEquals("/media/$port", first.getString("thumb"), "no Images: the original everywhere")
        assertEquals("/media/$port", first.getString("full"))
        assertEquals("The harbour", first.getString("caption"))
        assertEquals(null, (view.getArray("items")!![1] as Json.Object).getObject("captions")!!.getString("xx"), "a language the site does not speak is dropped")

        val page = visitor().get("/photos/plage").bodyAsText()
        assertContains(page, """<figure data-media="$port" data-captions="{&quot;en&quot;:&quot;The harbour&quot;}"><a href="/media/$port" data-full="/media/$port"><img src="/media/$port" alt="The harbour" loading="lazy"></a><figcaption>The harbour</figcaption></figure>""")
        assertFalse(page.contains("data-edit"), "a visitor sees the grid, not the tools")
        val editing = editor.get("/photos/plage").bodyAsText()
        assertContains(editing, """data-lang="en" data-edit="1">""")
        assertContains(editing, "/lib/sortablejs/Sortable.js?v=")
        assertContains(editing, "window.kroomGallery")

        // who may: editors and authors; a visitor is asked to log in, a stranger's media is refused
        assertEquals(HttpStatusCode.Unauthorized, visitor().json(HttpMethod.Put, "/api/gallery/$id", """{"items":[]}""").status)
        assertEquals(HttpStatusCode.OK, visitor("au").json(HttpMethod.Put, "/api/gallery/$id", """{"items":[{"media":"$plage","captions":{}}]}""").status)
        val refused = editor.json(HttpMethod.Put, "/api/gallery/$id", """{"items":[{"media":"k0000-nowhere.png","captions":{}}]}""")
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertContains(refused.bodyAsText(), """"code":"galleryMedia"""")
        assertEquals(listOf(plage), Json.parse(visitor().get("/api/gallery/$id").bodyAsText()).let { (it as Json.Object).getArray("items")!!.map { i -> (i as Json.Object).getString("media") } }, "a refused arrangement changes nothing")

        // the admin table, and a page without a gallery carries none of this
        val table = Json.parse(editor.get("/api/gallery").bodyAsText()) as Json.Object
        assertEquals(listOf(id), table.getArray("rows")!!.map { (it as Json.Array)[0] })
        editor.json(HttpMethod.Post, "/api/site/pages", """{"path":"/about"}""").let { assertEquals(HttpStatusCode.OK, it.status) }
        assertFalse(editor.get("/about").bodyAsText().contains("gallery.js"))
    }

    @Test
    fun `with an Images over the media, the grid and the show are renditions and the original stays for the zoom`() = testApplication {
        site(Gallery(Images(imgproxy = null, cache = root.resolve("cache").toFile(), local = root.resolve("media").toFile(), path = "/pictures")))
        val editor = visitor("ed")
        val id = editor.galleryPage("/photos")
        val plage = editor.upload("plage.png")
        editor.json(HttpMethod.Put, "/api/gallery/$id", """{"items":[{"media":"$plage","captions":{}}]}""").let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        val page = visitor().get("/photos").bodyAsText()
        assertContains(page, """<a href="/pictures/$plage?size=900" data-full="/media/$plage"><img src="/pictures/$plage?size=300"""")
        val served = visitor().get("/pictures/$plage?size=300")
        assertEquals(HttpStatusCode.OK, served.status, "no imgproxy: the original, through the images route the plugin mounted — on a path of its own, /img being the static files'")
        assertEquals(ContentType.Image.PNG, served.contentType())
    }
}
