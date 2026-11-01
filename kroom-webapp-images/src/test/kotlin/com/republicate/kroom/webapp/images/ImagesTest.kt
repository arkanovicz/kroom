package com.republicate.kroom.webapp.images

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.*

/** Pins the image route: renditions, the cache and its freshness, the fallbacks, signed remote sources. */
class ImagesTest {
    private val root = Files.createTempDirectory("images").toFile()
    private val uploads = File(root, "uploads").apply { mkdirs(); File(this, "a b.png").writeText("PNG") }
    private val asked = mutableListOf<String>()
    private var imgproxyUp = true
    private var remote: Fetched? = Fetched("image/png", "REMOTE".toByteArray())

    private fun images() = Images("http://imgproxy", File(root, "cache"), uploads) { url ->
        asked += url
        when {
            url.startsWith("http://imgproxy/") -> if (imgproxyUp) Fetched("image/webp", "WEBP".toByteArray()) else null
            else -> remote
        }
    }

    @AfterTest fun clean() { root.deleteRecursively() }

    private fun ApplicationTestBuilder.mount(images: Images) = routing { images(images) }

    @Test fun `a size snaps up to a rendition, the original beyond`() {
        assertNull(Images.rendition(0))
        assertEquals(Images.Rendition("fill", 32), Images.rendition(1))
        assertEquals(Images.Rendition("fill", 64), Images.rendition(48))
        assertEquals(Images.Rendition("fill", 300), Images.rendition(300))
        assertEquals(Images.Rendition("fit", 600), Images.rendition(301))
        assertEquals(Images.Rendition("fit", 900), Images.rendition(900))
        assertNull(Images.rendition(901))
    }

    @Test fun `a local image, original then rendered once, made again when its source changes`() = testApplication {
        val images = images()
        mount(images)
        assertEquals("/img/a%20b.png?size=48", images.url("a b.png", 48))
        client.get(images.url("a b.png")).let {
            assertEquals("PNG", it.bodyAsText())
            assertEquals("image/png", it.contentType()?.withoutParameters().toString())
            assertEquals("nosniff", it.headers["X-Content-Type-Options"])
        }
        client.get(images.url("a b.png", 48)).let { assertEquals("WEBP", it.bodyAsText()); assertEquals("image/webp", it.contentType().toString()) }
        client.get(images.url("a b.png", 64))
        val local = Base64.getUrlEncoder().withoutPadding().encodeToString("local:///a b.png".toByteArray())
        assertEquals(listOf("http://imgproxy/insecure/rs:fill:64:64/$local.webp"), asked)   // 48 and 64: one rendition
        File(uploads, "a b.png").setLastModified(System.currentTimeMillis() + 5000)
        client.get(images.url("a b.png", 64))
        assertEquals(2, asked.size)
    }

    @Test fun `imgproxy down, the original`() = testApplication {
        imgproxyUp = false
        val images = images()
        mount(images)
        assertEquals("PNG", client.get(images.url("a b.png", 64)).bodyAsText())
    }

    @Test fun `outside the root or absent, nothing`() = testApplication {
        File(root, "secret.png").writeText("NO")
        mount(images())
        assertEquals(HttpStatusCode.NotFound, client.get("/img/../secret.png").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/img/%2E%2E/secret.png").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/img/none.png").status)
    }

    @Test fun `a remote image only under this server's signature, fetched once, kept stale when unreachable`() = testApplication {
        val images = images()
        mount(images)
        val src = "https://cdn.example/avatars/42.png?size=128"
        val url = images.url(src)
        assertTrue(url.startsWith("/img/~/"))
        assertEquals("REMOTE", client.get(url).bodyAsText())
        assertEquals("REMOTE", client.get(url).bodyAsText())
        assertEquals(listOf(src), asked)
        assertEquals("WEBP", client.get(images.url(src, 32)).bodyAsText())

        val (_, sig, b64) = url.removePrefix("/img/").split('/')
        val other = Base64.getUrlEncoder().withoutPadding().encodeToString("https://evil.example/x.png".toByteArray())
        assertEquals(HttpStatusCode.NotFound, client.get("/img/~/$sig/$other").status)   // a signature is its source's
        assertEquals(HttpStatusCode.NotFound, client.get("/img/~/AAAAAAAAAAAAAAAAAAAAAA/$b64").status)

        // the same key from the same cache: a stored URL outlives a restart
        assertEquals(url, images().url(src))
    }

    @Test fun `a remote that is not a raster image is refused, a stale copy served`() = testApplication {
        val images = images()
        val ttl0 = Images("http://imgproxy", File(root, "cache"), uploads, path = "/expired", remoteTtl = kotlin.time.Duration.ZERO) { remote }
        routing { images(images); images(ttl0) }
        remote = Fetched("image/svg+xml", "<svg onload=alert(1)>".toByteArray())
        assertEquals(HttpStatusCode.BadGateway, client.get(images.url("https://cdn.example/x.svg")).status)
        remote = Fetched("image/png", "OLD".toByteArray())
        val old = ttl0.url("https://cdn.example/y.png")
        assertEquals("OLD", client.get(old).bodyAsText())
        remote = null
        assertEquals("OLD", client.get(old).bodyAsText())   // expired, unreachable: the stale copy
    }
}
