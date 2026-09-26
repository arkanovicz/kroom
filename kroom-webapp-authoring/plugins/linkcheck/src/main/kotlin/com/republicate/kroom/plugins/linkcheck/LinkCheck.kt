package com.republicate.kroom.plugins.linkcheck

import com.republicate.kroom.webapp.authoring.AdminEntry
import com.republicate.kroom.webapp.authoring.Permissions
import com.republicate.kroom.webapp.authoring.Plugin
import com.republicate.kroom.webapp.authoring.Setting
import com.republicate.kroom.webapp.authoring.Setting.Type.BOOLEAN
import com.republicate.kroom.webapp.authoring.Setting.Type.NUMBER
import com.republicate.kroom.webapp.authoring.Site
import com.republicate.kroom.webapp.core.respondError
import com.republicate.kroom.webapp.core.respondJson
import com.republicate.kroom.webapp.session.userSession
import com.republicate.kson.Json
import io.ktor.http.*
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

private val logger = LoggerFactory.getLogger("kroom.plugins.linkcheck")

/**
 * Nothing linkrots silently: every [periodHours], each page the site serves ([Site.pages]) is fetched from
 * `baseUrl`, and every link and picture on it tried — the site's own, and others' unless told not to. What
 * answers 4xx/5xx or nothing is listed in the admin bar, with the page it sits on; the list is the last run's.
 *
 * [http] is how a URL is fetched — the network by default; a test hands its own.
 */
class LinkCheck(private val http: suspend (String) -> Answer = ::fetch) : Plugin {
    override val id = "linkcheck"
    override val name = "Link check"
    override val description = "Every page walked, every link and picture tried, the broken ones listed"
    override val settings = listOf(
        Setting("baseUrl", "Site URL", help = "Where the site answers, as its visitors reach it — nothing is checked while empty"),
        Setting("external", "Check other sites' links too", default = "true", type = BOOLEAN),
        Setting("periodHours", "Every (hours)", default = "24", type = NUMBER)
    )

    /** What a URL answered: its status (0 when nothing did) and, for a page of ours, its html. */
    class Answer(val status: Int, val html: String? = null)

    @Volatile private var lastRun = 0L

    override fun install(site: Site) {
        val settings = site.settings(this)
        site.every(1.hours) {
            val period = (settings["periodHours"]?.toLongOrNull() ?: 24).hours.inWholeMilliseconds
            if (System.currentTimeMillis() - lastRun >= period) run(site)
        }
        site.admin(AdminEntry("linkcheck", "Broken links", icon = "M9 15l6-6M11 6l.5-.5a4 4 0 0 1 5.66 5.66l-.5.5M13 18l-.5.5a4 4 0 0 1-5.66-5.66l.5-.5M4 4l16 16", table = "/api/linkcheck"))
        site.routes {
            get("/api/linkcheck") {
                if (!site.can(call.userSession, Permissions.ADMIN))
                    return@get respondError("not an administrator", HttpStatusCode.Forbidden, "notAdmin")
                val columns = listOf("page", "link", "status", "checked")
                respondJson {
                    set("columns", Json.MutableArray().apply { columns.forEach { push(it) } })
                    set("rows", Json.MutableArray().apply {
                        site.records(this@LinkCheck, "broken").list(500).values.forEach { row ->
                            push(Json.MutableArray().apply {
                                push(row.getString("page")); push(row.getString("link")); push(row.getInteger("status"))
                                push(Instant.ofEpochMilli(row.getLong("checked") ?: 0).toString())
                            })
                        }
                    })
                }
            }
        }
    }

    /** One walk: answers the broken links found, and keeps them as the list the admin bar shows. */
    suspend fun run(site: Site): List<Pair<String, String>> {
        lastRun = System.currentTimeMillis()
        val settings = site.settings(this)
        val base = settings["baseUrl"]?.trim()?.trimEnd('/').orEmpty().takeIf { it.isNotEmpty() } ?: return emptyList()
        val external = settings["external"] == "true"
        val tried = HashMap<String, Int>()
        val broken = ArrayList<Triple<String, String, Int>>()
        for (page in site.pages().flatMap { it.urls }.distinct()) {
            val answer = http(base + page)
            if (answer.status !in 200..399) { broken += Triple(page, page, answer.status); continue }
            for (link in links(answer.html.orEmpty(), base + page)) {
                if (!external && !link.startsWith(base)) continue
                if (tried.size >= MAX_LINKS && link !in tried) break
                val status = tried.getOrPut(link) { http(link).status }
                if (status !in 200..399) broken += Triple(page, link.removePrefix(base), status)
            }
        }
        val records = site.records(this, "broken")
        records.list(Int.MAX_VALUE).keys.forEach { records.delete(it) }
        broken.forEach { (page, link, status) ->
            records.add(Json.MutableObject().apply { set("page", page); set("link", link); set("status", status); set("checked", lastRun) })
        }
        logger.info("linkcheck: {} pages, {} links, {} broken", site.pages().sumOf { it.urls.size }, tried.size, broken.size)
        return broken.map { it.first to it.second }
    }

    companion object {
        private const val MAX_LINKS = 2000
        private val REFERENCE = Regex("""(?:href|src)\s*=\s*"([^"]+)"""")
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build()

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
            val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15)).GET()
                .header("User-Agent", "kroom-linkcheck").build()
            val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            val html = response.body().takeIf { response.headers().firstValue("content-type").orElse("").startsWith("text/html") }
            Answer(response.statusCode(), html)
        } catch (e: Exception) {
            Answer(0)
        }
    }
}
