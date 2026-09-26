package com.republicate.kroom.plugins.redirects

import com.republicate.kroom.webapp.authoring.AdminEntry
import com.republicate.kroom.webapp.authoring.Permissions
import com.republicate.kroom.webapp.authoring.Plugin
import com.republicate.kroom.webapp.authoring.Setting
import com.republicate.kroom.webapp.authoring.Setting.Type.TEXTAREA
import com.republicate.kroom.webapp.authoring.Site
import com.republicate.kroom.webapp.core.respondError
import com.republicate.kroom.webapp.core.respondJson
import com.republicate.kroom.webapp.session.userSession
import com.republicate.kson.Json
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Old URLs answered before routing, so a moved page keeps its links and its search ranking. The rules are one
 * setting, a line each — `/old /new`, a trailing `*` on both sides carrying the rest of the path over, an optional status
 * (301 by default) — edited in the admin bar, whose table shows each rule as understood and how often it hit.
 */
class Redirects : Plugin {
    override val id = "redirects"
    override val name = "Redirects"
    override val description = "Old URLs sent to new ones, before any page is looked for"
    override val settings = listOf(
        Setting("rules", "Rules", type = TEXTAREA, help = "One per line: /from /to [301|302|307|308]; a trailing * carries the rest over")
    )

    data class Rule(val from: String, val to: String, val status: Int) {
        private val prefix = from.endsWith("*")

        /** Where [path] goes, or null when this rule does not take it. */
        fun target(path: String): String? = when {
            prefix && path.startsWith(from.dropLast(1)) -> to.removeSuffix("*") + if (to.endsWith("*")) path.removePrefix(from.dropLast(1)) else ""
            path == from -> to
            else -> null
        }
    }

    /** Rules as understood, and the lines that were not — the admin must see both. */
    class Parsed(val rules: List<Rule>, val errors: List<String>)

    companion object {
        fun parse(text: String): Parsed {
            val rules = ArrayList<Rule>()
            val errors = ArrayList<String>()
            text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
                val parts = line.split(Regex("\\s+"))
                val status = parts.getOrNull(2)?.toIntOrNull() ?: 301
                if (parts.size !in 2..3 || !parts[0].startsWith("/") || status !in setOf(301, 302, 307, 308)) errors += line
                else rules += Rule(parts[0], parts[1], status)
            }
            return Parsed(rules, errors)
        }
    }

    private val hits = ConcurrentHashMap<String, AtomicLong>()

    // parsed once per distinct text: the settings are read per request, and change a few times a year
    @Volatile private var cache: Pair<String, Parsed> = "" to Parsed(emptyList(), emptyList())

    private fun rules(site: Site): Parsed {
        val text = site.settings(this)["rules"].orEmpty()
        return cache.takeIf { it.first == text }?.second ?: parse(text).also { cache = text to it }
    }

    override fun install(site: Site) {
        site.intercept { call ->
            val path = call.request.path()
            for (rule in rules(site).rules) {
                val target = rule.target(path) ?: continue
                hits.computeIfAbsent(rule.from) { AtomicLong() }.incrementAndGet()
                call.response.headers.append(HttpHeaders.Location, target)
                call.respond(HttpStatusCode.fromValue(rule.status))
                return@intercept
            }
        }

        site.admin(AdminEntry("redirects", "Redirects", icon = "M4 12h13M13 6l6 6-6 6M4 5v14", table = "/api/redirects"))

        site.routes {
            get("/api/redirects") {
                if (!site.can(call.userSession, Permissions.ADMIN))
                    return@get respondError("not an administrator", HttpStatusCode.Forbidden, "notAdmin")
                val parsed = rules(site)
                respondJson {
                    set("columns", Json.MutableArray().apply { listOf("from", "to", "status", "hits").forEach { push(it) } })
                    set("rows", Json.MutableArray().apply {
                        parsed.rules.forEach { rule ->
                            push(Json.MutableArray().apply { push(rule.from); push(rule.to); push(rule.status); push(hits[rule.from]?.get() ?: 0L) })
                        }
                        parsed.errors.forEach { line ->
                            push(Json.MutableArray().apply { push(line); push("not understood"); push(null); push(null) })
                        }
                    })
                }
            }
        }
    }
}
