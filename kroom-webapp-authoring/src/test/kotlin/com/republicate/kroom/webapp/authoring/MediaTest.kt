package com.republicate.kroom.webapp.authoring

import com.republicate.kroom.webapp.session.UserSession
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
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What may be uploaded is told by its bytes; what is uploaded is served to anyone, never replaced. */
class MediaTest {

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) + ByteArray(100)
    private val svg = """<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>""".toByteArray()

    private fun ApplicationTestBuilder.site(storage: Storage = MemoryStorage()) = application {
        installContentSite {
            this.storage = storage
            sessionSecret = "test-secret"
            identity = IdentityProvider { mapOf("admin" to setOf(Roles.ADMIN), "editor" to setOf(Roles.EDITOR))[it.id].orEmpty() }
        }
        routing { get("/as/{who}") { call.sessions.set(UserSession(call.parameters["who"]!!, "x", null, "t")); call.respondText("ok") } }
    }

    private suspend fun ApplicationTestBuilder.as_(who: String?): HttpClient =
        createClient { install(HttpCookies) }.also { if (who != null) it.get("/as/$who") }

    private suspend fun HttpClient.upload(name: String, bytes: ByteArray) =
        post("/api/content/media?name=$name") { setBody(bytes) }

    @Test
    fun `an editor uploads a picture, anyone gets it, immutable`() = testApplication {
        site()
        val answer = as_("editor").upload("Photo du Club!.PNG", png)
        assertEquals(HttpStatusCode.OK, answer.status)
        val url = Regex(""""url":"([^"]+)"""").find(answer.bodyAsText())!!.groupValues[1]
        assertContains(url, "-photo-du-club.png")

        val served = as_(null).get(url)
        assertEquals(ContentType.Image.PNG, served.contentType()?.withoutParameters())
        assertContains(served.headers[HttpHeaders.CacheControl]!!, "immutable")
        assertContentEquals(png, served.readRawBytes())
    }

    @Test
    fun `the bytes decide — no svg, no claimed type`() = testApplication {
        site()
        assertEquals(HttpStatusCode.UnsupportedMediaType, as_("editor").upload("logo.png", svg).status)
    }

    @Test
    fun `a visitor uploads nothing, an editor deletes nothing`() = testApplication {
        site()
        assertEquals(HttpStatusCode.Unauthorized, as_(null).upload("a.png", png).status)
        val editor = as_("editor")
        val name = Regex(""""name":"([^"]+)"""").find(editor.upload("a.png", png).bodyAsText())!!.groupValues[1]
        assertEquals(HttpStatusCode.Forbidden, editor.delete("/api/content/media/$name").status)
        assertEquals(HttpStatusCode.OK, as_("admin").delete("/api/content/media/$name").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/media/$name").status)
    }

    @Test
    fun `a full store refuses rather than forgets`() = testApplication {
        site(MemoryStorage(mediaCapacity = 150))
        val editor = as_("editor")
        assertEquals(HttpStatusCode.OK, editor.upload("a.png", png).status)
        assertEquals(HttpStatusCode.InsufficientStorage, editor.upload("b.png", png).status)
    }

    @Test
    fun `both stores keep the same files`() {
        for (storage in listOf(MemoryStorage(), FileStorage(Files.createTempDirectory("kroom-media")))) {
            val file = storage.media.put("a.png", "image/png", png)
            assertEquals(file, storage.media.get(file.name)!!.copy(time = file.time))
            assertEquals(listOf(file.name), storage.media.list().map { it.name })
            assertNull(storage.media.get("../content/secret.png"))
        }
    }
}
