package com.republicate.kroom.webapp.images

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

private val logger = LoggerFactory.getLogger("kroom.images")

/** What a fetch brought back: its content type and its bytes. */
class Fetched(val type: String, val bytes: ByteArray)

/**
 * Images served resized through imgproxy and kept on disk, from local files (uploads) and remote sources (avatars
 * on a CDN) alike: a viewer only ever hits this server. A size snaps up to a rendition, `fill` (a cropped square)
 * up to 300, `fit` up to 900, the original beyond; a rendition is webp, kept in [cache] keyed by source and
 * rendition, made again when a local source is newer, after [remoteTtl] for a remote one. imgproxy failing, the
 * original is served. A remote source reaches the route only through a URL this server signed ([url]), so the
 * route is no open proxy.
 *
 * imgproxy stays on the private network (`insecure` URLs), reads [local] as `local:///`
 * (`IMGPROXY_LOCAL_FILESYSTEM_ROOT`), and may restrict its own fetches (`IMGPROXY_ALLOWED_SOURCES`).
 */
class Images(
    /** imgproxy's base URL (`http://arkad-img:8080`); null serves originals only. */
    val imgproxy: String?,
    /** Renditions and remote originals, `<cache>/<2 hex>/<sha256>-<rendition>.<ext>`; the signing key when none is given. */
    val cache: File,
    /** The root of local sources, as imgproxy sees it under `local:///`; null: remote sources only. */
    val local: File? = null,
    /** The key remote URLs are signed with; null: `<cache>/secret`, created once (stored URLs outlive a restart). */
    secret: ByteArray? = null,
    /** Where the route is mounted, what [url] answers under. */
    val path: String = "/img",
    /** How long a remote image is kept before it is fetched again (a failed fetch serves the stale copy). */
    val remoteTtl: Duration = 7.days,
    private val http: suspend (String) -> Fetched? = ::fetch
) {
    private val key = SecretKeySpec(secret ?: storedSecret(), "HmacSHA256")

    /** A rendition: how imgproxy resizes, and to what square. */
    data class Rendition(val mode: String, val size: Int) { override fun toString() = "$mode$size" }

    /** The URL serving [source] (a path under [local], or an `http(s)` URL) at [size] (0: the original). */
    fun url(source: String, size: Int = 0): String {
        val query = if (size > 0) "?size=$size" else ""
        return if (remote(source)) "$path/$REMOTE/${sign(source)}/${b64(source)}$query"
        else "$path/" + source.trimStart('/').split('/').joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") } + query
    }

    private sealed interface Source { val key: String }
    private class Local(val file: File, val rel: String) : Source { override val key get() = "local:///$rel" }
    private class Remote(val url: String) : Source { override val key get() = url }

    internal suspend fun serve(call: ApplicationCall, segments: List<String>) {
        val source = (if (segments.firstOrNull() == REMOTE) remoteSource(segments) else localSource(segments))
            ?: return call.respond(HttpStatusCode.NotFound)
        val rendition = rendition(call.request.queryParameters["size"]?.toIntOrNull() ?: 0)
        val file = withContext(Dispatchers.IO) { rendition?.let { rendered(source, it) } ?: original(source) }
            ?: return call.respond(HttpStatusCode.BadGateway)
        call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Content-Security-Policy", "default-src 'none'")   // an SVG opened alone runs nothing
        call.respond(LocalFileContent(file, TYPES.entries.firstOrNull { it.value == file.extension }?.let { ContentType.parse(it.key) } ?: ContentType.defaultForFile(file)))
    }

    private fun localSource(segments: List<String>): Local? {
        val root = local ?: return null
        val rel = segments.joinToString("/")
        val file = File(root, rel)
        return Local(file, rel).takeIf { rel.isNotEmpty() && file.isFile && file.canonicalPath.startsWith(root.canonicalPath + File.separator) }
    }

    private fun remoteSource(segments: List<String>): Remote? {
        if (segments.size != 3) return null
        val url = runCatching { String(Base64.getUrlDecoder().decode(segments[2]), Charsets.UTF_8) }.getOrNull() ?: return null
        return Remote(url).takeIf { remote(url) && MessageDigest.isEqual(sign(url).toByteArray(), segments[1].toByteArray()) }
    }

    /** The rendition kept, made when missing or stale; null when imgproxy cannot make it. */
    private suspend fun rendered(source: Source, rendition: Rendition): File? {
        val base = imgproxy ?: return null
        val file = cached(source, rendition.toString(), "webp")
        if (fresh(file, source)) return file
        val made = http("$base/insecure/rs:${rendition.mode}:${rendition.size}:${rendition.size}/${b64(source.key)}.webp")
        if (made == null || made.type != "image/webp") {
            logger.warn("imgproxy could not render {} at {}: {}", source.key, rendition, made?.type ?: "no answer")
            return file.takeIf { it.isFile }   // stale beats nothing
        }
        return write(file, made.bytes)
    }

    /** The source itself: the file for a local one, the fetched copy (kept) for a remote one; null when unreachable. */
    private suspend fun original(source: Source): File? = when (source) {
        is Local -> source.file
        is Remote -> {
            val kept = cacheDir(source).listFiles { f -> f.name.startsWith("${hash(source.key)}-original.") }?.firstOrNull()
            if (kept != null && fresh(kept, source)) kept
            else {
                val fetched = http(source.url)
                val ext = fetched?.let { TYPES[it.type] }
                if (fetched == null || ext == null) {
                    logger.warn("could not fetch {}: {}", source.url, fetched?.type ?: "no answer")
                    kept
                } else write(cached(source, "original", ext), fetched.bytes).also { if (kept != null && kept.extension != ext) kept.delete() }
            }
        }
    }

    private fun fresh(file: File, source: Source): Boolean = file.isFile && when (source) {
        is Local -> file.lastModified() >= source.file.lastModified()
        is Remote -> System.currentTimeMillis() - file.lastModified() < remoteTtl.inWholeMilliseconds
    }

    private fun cacheDir(source: Source) = File(cache, hash(source.key).take(2))
    private fun cached(source: Source, rendition: String, ext: String) = File(cacheDir(source), "${hash(source.key)}-$rendition.$ext")

    // two requests may make the same file: each writes its own, the last move wins whole
    private fun write(file: File, bytes: ByteArray): File {
        file.parentFile.mkdirs()
        val part = File.createTempFile(file.name, ".part", file.parentFile)
        part.writeBytes(bytes)
        Files.move(part.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return file
    }

    private fun sign(source: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(source.toByteArray()).copyOf(16))

    private fun storedSecret(): ByteArray {
        val file = File(cache, "secret")
        if (file.isFile) return file.readBytes().also { logger.info("images: signing key read from {}", file) }
        cache.mkdirs()
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        Files.write(file.toPath(), bytes)
        runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
        logger.info("images: signing key created in {}", file)
        return bytes
    }

    companion object {
        /** The squares a size snaps up to: cropped up to 300, fitted up to 900. */
        val FILL = listOf(32, 64, 128, 300)
        val FIT = listOf(600, 900)

        /** What [size] asks for; null: the original (0, or beyond the largest). */
        fun rendition(size: Int): Rendition? = when {
            size <= 0 -> null
            size <= FILL.last() -> Rendition("fill", FILL.first { it >= size })
            size <= FIT.last() -> Rendition("fit", FIT.first { it >= size })
            else -> null
        }

        // the types a remote original may be, by extension: no SVG, which carries scripts
        private val TYPES = mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif", "image/webp" to "webp", "image/avif" to "avif")
        private const val REMOTE = "~"
        private const val MAX_BYTES = 20 shl 20

        private fun remote(source: String) = source.startsWith("https://") || source.startsWith("http://")
        private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        private val client: HttpClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build()

        /** A GET, 200 or nothing, capped at 20 MB. */
        suspend fun fetch(url: String): Fetched? = runCatching {
            val request = HttpRequest.newBuilder(URI(url)).timeout(java.time.Duration.ofSeconds(15)).GET().build()
            val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).await()
            response.body().use { body ->
                if (response.statusCode() != 200) return@runCatching null
                val bytes = withContext(Dispatchers.IO) { body.readNBytes(MAX_BYTES + 1) }
                if (bytes.size > MAX_BYTES) null
                else Fetched(response.headers().firstValue("Content-Type").orElse("").substringBefore(';').trim().lowercase(), bytes)
            }
        }.getOrNull()
    }
}

/** Serves [images] under its path; guard it like any route (a wall, a session) by mounting it inside one. */
fun Route.images(images: Images) {
    get("${images.path}/{path...}") { images.serve(call, call.parameters.getAll("path").orEmpty()) }
}
