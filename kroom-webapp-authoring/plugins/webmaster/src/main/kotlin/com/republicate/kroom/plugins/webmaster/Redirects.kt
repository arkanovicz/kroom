package com.republicate.kroom.plugins.webmaster

import com.republicate.kroom.webapp.authoring.Site
import com.republicate.kson.Json
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.days

/**
 * One redirect rule, `from to [status]`:
 *
 * ```
 * /old                              /new
 * /tournoi.php?id={id}              /tournament/{id}            captures, in the path or the query
 * /affichePersonne.php?id={id}      @player                     an application's resolver, given the captures
 * ```
 *
 * and a trailing `*` on both sides carries the rest of the path over.
 */
class RedirectRule(val from: String, val to: String, val status: Int) {
    private val path = from.substringBefore('?')
    private val prefix = path.endsWith("*")
    private val names = CAPTURE.findAll(path).map { it.groupValues[1] }.toList()
    private val pathRegex = Regex(
        path.removeSuffix("*").split(CAPTURE_SPLIT).joinToString("") { part ->
            CAPTURE.matchEntire(part)?.let { "([^/]+)" } ?: Regex.escape(part)
        } + if (prefix) "(.*)" else ""
    )
    // name=literal must be there as is; name={capture} binds whatever value it has
    private val query: List<Pair<String, String>> = from.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
        .map { it.substringBefore('=') to it.substringAfter('=', "") }

    /** What this rule captured from [path] and [parameters], or null when it does not take them. */
    fun match(path: String, parameters: Parameters): Map<String, String>? {
        val found = pathRegex.matchEntire(path) ?: return null
        val values = LinkedHashMap<String, String>()
        names.forEachIndexed { i, name -> values[name] = found.groupValues[i + 1] }
        if (prefix) values["*"] = found.groupValues.last()
        for ((key, expected) in query) {
            val actual = parameters[key] ?: return null
            val capture = CAPTURE.matchEntire(expected)?.groupValues?.get(1)
            if (capture != null) values[capture] = actual else if (actual != expected) return null
        }
        return values
    }

    /** The target, captures filled in, each one encoded: a value never adds a segment, let alone a host. */
    fun target(values: Map<String, String>, resolvers: Map<String, (Map<String, String>) -> String?>): String? =
        if (to.startsWith("@")) resolvers[to.drop(1)]?.invoke(values)
        else CAPTURE.replace(to.removeSuffix("*")) { values[it.groupValues[1]]?.encodeURLPathPart() ?: "" } +
            (if (to.endsWith("*")) values["*"].orEmpty() else "")

    companion object {
        private val CAPTURE = Regex("\\{([A-Za-z][A-Za-z0-9]*)}")
        private val CAPTURE_SPLIT = Regex("(?=\\{[A-Za-z][A-Za-z0-9]*})|(?<=})")

        /** Rules as understood, and the lines that were not — the admin must see both. */
        fun parse(text: String): Pair<List<RedirectRule>, List<String>> {
            val rules = ArrayList<RedirectRule>()
            val errors = ArrayList<String>()
            text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
                val parts = line.split(Regex("\\s+"))
                val status = parts.getOrNull(2)?.toIntOrNull() ?: 301
                if (parts.size !in 2..3 || !parts[0].startsWith("/") || status !in setOf(301, 302, 307, 308)) errors += line
                else rules += RedirectRule(parts[0], parts[1], status)
            }
            return rules to errors
        }
    }
}

private const val MAX_MISSES = 1000

/**
 * Old URLs answered before routing, so a moved page keeps its links and its search ranking; and what nothing
 * answered, counted by URL — the next rule to write, in plain sight.
 */
internal fun Webmaster.installRedirects(site: Site) {
    val misses = site.records(this, "misses")
    val hits = ConcurrentHashMap<String, AtomicLong>()
    // parsed once per distinct text: the settings are read per request, and change a few times a year
    var cache: Pair<String, Pair<List<RedirectRule>, List<String>>> = "" to (emptyList<RedirectRule>() to emptyList())
    fun rules(): Pair<List<RedirectRule>, List<String>> {
        val text = site.settings(this)["rules"].orEmpty()
        return cache.takeIf { it.first == text }?.second ?: RedirectRule.parse(text).also { cache = text to it }
    }

    site.intercept { call ->
        val path = call.request.path()
        for (rule in rules().first) {
            val values = rule.match(path, call.request.queryParameters) ?: continue
            val target = rule.target(values, resolvers) ?: continue
            hits.computeIfAbsent(rule.from) { AtomicLong() }.incrementAndGet()
            call.response.headers.append(HttpHeaders.Location, target)
            call.respond(HttpStatusCode.fromValue(rule.status))
            return@intercept
        }
    }

    site.notFound { call ->
        val uri = call.request.uri.take(500)
        val id = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        val seen = misses.get(id)
        if (seen == null && misses.list(MAX_MISSES).size >= MAX_MISSES) return@notFound
        misses.put(id, Json.MutableObject().apply {
            set("uri", uri)
            set("count", (seen?.getLong("count") ?: 0L) + 1)
            set("last", System.currentTimeMillis())
        })
    }
    site.every(1.days) {
        val cutoff = System.currentTimeMillis() - 30.days.inWholeMilliseconds
        misses.list(Int.MAX_VALUE).forEach { (id, miss) -> if ((miss.getLong("last") ?: 0) < cutoff) misses.delete(id) }
    }

    site.routes {
        get("${Webmaster.API}/redirects") {
            if (!admin(site)) return@get
            val (rules, errors) = rules()
            respondTable(listOf("from", "to", "status", "hits"),
                rules.map { listOf(it.from, it.to, it.status, hits[it.from]?.get() ?: 0L) } +
                    errors.map { listOf(it, "not understood", null, null) })
        }
        get("${Webmaster.API}/missing") {
            if (!admin(site)) return@get
            respondTable(listOf("uri", "count", "last"),
                misses.list(Int.MAX_VALUE).values.sortedByDescending { it.getLong("count") ?: 0 }.take(200)
                    .map { listOf(it.getString("uri"), it.getLong("count"), Instant.ofEpochMilli(it.getLong("last") ?: 0).toString()) })
        }
    }
}
