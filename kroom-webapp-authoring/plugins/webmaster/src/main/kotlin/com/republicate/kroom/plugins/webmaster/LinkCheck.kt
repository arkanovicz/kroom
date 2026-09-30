package com.republicate.kroom.plugins.webmaster

import com.republicate.kroom.webapp.authoring.Site
import com.republicate.kroom.webapp.authoring.respondTable
import com.republicate.kson.Json
import io.ktor.server.routing.*
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.hours

private val logger = LoggerFactory.getLogger("kroom.plugins.webmaster")

/** What a URL answered: its status (0 when nothing did) and, for a page, its html. */
class Answer(val status: Int, val html: String? = null)

private const val MAX_LINKS = 2000
private val REFERENCE = Regex("""(?:href|src)\s*=\s*"([^"]+)"""")
private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()

/** The http(s) URLs a page references, resolved against it; anchors, mail and scripts left out. */
fun links(html: String, page: String): Set<String> =
    REFERENCE.findAll(html).mapNotNull { match ->
        val reference = match.groupValues[1].replace("&amp;", "&").trim()
        if (reference.startsWith("#") || reference.isEmpty()) null
        else runCatching { URI(page).resolve(reference) }.getOrNull()
            ?.takeIf { it.scheme == "http" || it.scheme == "https" }
            ?.let { URI(it.scheme, it.authority, it.path, it.query, null).toString() }
    }.toSet()

suspend fun fetch(url: String): Answer = try {
    val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15)).GET().header("User-Agent", "kroom-webmaster").build()
    val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
    val html = response.body().takeIf { response.headers().firstValue("content-type").orElse("").startsWith("text/html") }
    Answer(response.statusCode(), html)
} catch (e: Exception) {
    Answer(0)
}

/**
 * Nothing linkrots silently: every `periodHours`, each page the site serves is fetched from its public URL, and
 * every link and picture on it tried — the site's own, and others' unless told not to. What answers 4xx/5xx or
 * nothing is listed with its page; the list is the last walk's.
 */
internal fun Webmaster.installLinkCheck(site: Site) {
    var lastRun = 0L
    site.every(1.hours) {
        val period = (site.settings(this)["periodHours"]?.toLongOrNull() ?: 24).hours.inWholeMilliseconds
        if (System.currentTimeMillis() - lastRun >= period) { lastRun = System.currentTimeMillis(); checkLinks(site) }
    }
    site.routes {
        get("${Webmaster.API}/broken") {
            if (!admin(site)) return@get
            respondTable(listOf("page", "link", "status", "checked"),
                site.records(this@installLinkCheck, "broken").list(500).values.map {
                    listOf(it.getString("page"), it.getString("link"), it.getInteger("status"), Instant.ofEpochMilli(it.getLong("checked") ?: 0).toString())
                })
        }
    }
}

/** One walk: answers the broken links found, page to link, and keeps them as the list the admin bar shows. */
suspend fun Webmaster.checkLinks(site: Site): List<Pair<String, String>> {
    val base = site.baseUrl.takeIf { it.isNotEmpty() } ?: return emptyList()
    val external = site.settings(this)["external"] == "true"
    val tried = HashMap<String, Int>()
    val broken = ArrayList<Triple<String, String, Int>>()
    val pages = site.pages().flatMap { it.urls }.distinct()
    for (page in pages) {
        val answer = http(base + page)
        if (answer.status !in 200..399) { broken += Triple(page, page, answer.status); continue }
        for (link in links(answer.html.orEmpty(), base + page)) {
            if (!external && !link.startsWith(base)) continue
            if (tried.size >= MAX_LINKS && link !in tried) break
            val status = tried.getOrPut(link) { http(link).status }
            if (status !in 200..399) broken += Triple(page, link.removePrefix(base), status)
        }
    }
    val now = System.currentTimeMillis()
    val records = site.records(this, "broken")
    records.list(Int.MAX_VALUE).keys.forEach { records.delete(it) }
    broken.forEach { (page, link, status) ->
        records.add(Json.MutableObject().apply { set("page", page); set("link", link); set("status", status); set("checked", now) })
    }
    logger.info("link check: {} pages, {} links, {} broken", pages.size, tried.size, broken.size)
    return broken.map { it.first to it.second }
}
