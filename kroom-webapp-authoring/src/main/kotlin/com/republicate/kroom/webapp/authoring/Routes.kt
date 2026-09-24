package com.republicate.kroom.webapp.authoring

import com.republicate.kroom.webapp.core.receiveJsonObject
import com.republicate.kroom.webapp.core.respondError
import com.republicate.kroom.webapp.core.respondJson
import com.republicate.kroom.webapp.core.respondSuccess
import com.republicate.kroom.webapp.session.UserSession
import com.republicate.kroom.webapp.session.userSession
import com.republicate.kroom.webapp.velocity.resolvePage
import com.republicate.kroom.webapp.velocity.velocity
import com.republicate.kroom.webapp.velocity.velocityOrNull
import com.republicate.kson.Json
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * The edit API, mounted under [AuthoringConfig.apiPrefix]:
 *
 * ```
 * GET    {path...}[?rev=]   the block, its lock and whether the caller may edit it
 * POST   /lock/{path...}    take the block (409 when someone else holds it)
 * DELETE /lock/{path...}    give it back, unwritten
 * POST   {path...}          submit {page, rev, body} (409 on a stale rev, with theirs; 422 if it breaks the page)
 * POST   /preview/{path...} render {page, body} — the page itself, this body standing in (422 if it breaks)
 * GET    /history/{path...} revisions of one block   ] 404 unless the store is Versioned
 * GET    /journal           the site-wide log        ]
 * ```
 *
 * The lock routes read `/lock/…` rather than `…/lock`: a ktor tailcard takes every remaining segment, so
 * nothing can follow `{path...}` in a route pattern.
 *
 * A submit carries the rev the author started from, so a block edited meanwhile is answered with *theirs*
 * instead of being overwritten — the lock is the polite path, the rev check is the safe one.
 */
/** The context key `#markdown` reads a draft from (kroom-markdown's `MarkdownMacro.DRAFTS`). */
private const val DRAFTS = "kroomDrafts"

fun Route.authoringRoutes() {
    val plugin = application.authoring

    route(plugin.apiPrefix) {

        get("/journal") {
            editorOf(plugin, "") ?: return@get
            val versioned = versionedStore(plugin) ?: return@get
            respondJson(revisions(versioned.log(null, limit())))
        }

        get("/history/{path...}") {
            val path = blockPath() ?: return@get
            editorOf(plugin, path) ?: return@get
            val versioned = versionedStore(plugin) ?: return@get
            respondJson(revisions(versioned.log(path, limit())))
        }

        // A preview is the page a visitor would get, rendered with the text being written in place of the
        // stored block: no second renderer to keep in step, and what you see is what you are about to save.
        post("/preview/{path...}") {
            val path = blockPath() ?: return@post
            val session = editorOf(plugin, path) ?: return@post
            if (!plugin.locks.touch(path, session.id))
                return@post respondError("the lock on $path is not yours", HttpStatusCode.Conflict)

            val asked = receiveJsonObject()
            val html = renderDraft(asked, path) ?: return@post
            respondJson { set("page", html) }
        }

        route("/lock/{path...}") {
            post {
                val path = blockPath() ?: return@post
                val session = editorOf(plugin, path) ?: return@post
                val lock = plugin.locks.acquire(path, session.id)
                    ?: return@post respondError(
                        "block held by ${plugin.locks.holder(path)?.owner}", HttpStatusCode.Conflict
                    )
                respondJson(payload(plugin, path, plugin.store.read(path), lock, session))
            }

            delete {
                val path = blockPath() ?: return@delete
                val session = editorOf(plugin, path) ?: return@delete
                plugin.locks.release(path, session.id)
                respondSuccess()
            }
        }

        get("{path...}") {
            val path = blockPath() ?: return@get
            val session = editorOf(plugin, path) ?: return@get
            val asked = call.request.queryParameters["rev"]
            val block = when (asked) {
                null -> plugin.store.read(path)
                else -> (plugin.store as? Versioned)?.read(path, asked)
                    ?: return@get respondError("no such revision: $asked", HttpStatusCode.NotFound)
            }
            respondJson(payload(plugin, path, block, plugin.locks.holder(path), session))
        }

        post("{path...}") {
            val path = blockPath() ?: return@post
            val session = editorOf(plugin, path) ?: return@post
            // the same call refreshes the lock and proves it is his — a submit without it is a lost edit
            if (!plugin.locks.touch(path, session.id))
                return@post respondError("the lock on $path is not yours", HttpStatusCode.Conflict)

            val submitted = receiveJsonObject()
            // what would break the page once published is refused now, while its author is still there
            // (no velocity, no page to break: the edit API stands on its own)
            if (application.velocityOrNull != null) renderDraft(submitted, path) ?: return@post
            val theirs = plugin.store.read(path)
            if ((submitted.getString("rev") ?: "") != (theirs?.rev ?: "")) {
                return@post respondJson(HttpStatusCode.Conflict) {
                    set("message", "$path changed since you started editing")
                    set("theirs", Json.MutableObject().apply {
                        set("body", theirs?.body ?: "")
                        set("rev", theirs?.rev ?: "")
                    })
                }
            }

            val written = plugin.store.write(
                path, submitted.getString("body") ?: "",
                mapOf("author" to session.id, "updated" to System.currentTimeMillis().toString())
            )
            plugin.locks.release(path, session.id)
            respondJson {
                set("rev", written.rev)
                set("meta", meta(written))
            }
        }
    }
}

/**
 * The page at `page`, rendered with `body` standing in for the block at [path] — or null having answered:
 * 400/404 for a missing or unknown page, 422 with the author's problem when the body breaks the render.
 */
private suspend fun RoutingContext.renderDraft(asked: Json.Object, path: String): String? {
    val page = asked.getString("page")
    if (page == null) {
        respondError("a block is rendered within the page it is in: page missing")
        return null
    }
    val (template, bound) = call.application.resolvePage(page) ?: run {
        respondError("no page at $page", HttpStatusCode.NotFound)
        return null
    }
    return try {
        call.application.velocity.renderForCall(
            call, template, bound + mapOf(DRAFTS to mapOf(path to (asked.getString("body") ?: "")))
        )
    } catch (e: Exception) {
        // the engine's wrappers say where it surfaced; the innermost cause says what the author wrote wrong
        val cause = generateSequence<Throwable>(e) { it.cause }.last { it.message != null }
        respondError(cause.message!!, HttpStatusCode.UnprocessableEntity)
        null
    }
}

/** The block path, or null having answered 400 — an empty or traversing path never reaches a store. */
private suspend fun RoutingContext.blockPath(): String? {
    val segments = call.parameters.getAll("path").orEmpty().filter { it.isNotEmpty() }
    if (segments.isEmpty() || segments.any { it == ".." }) {
        respondError("invalid content path")
        return null
    }
    return segments.joinToString("/")
}

/** The caller if he may edit [path], or null having answered 401/403. */
private suspend fun RoutingContext.editorOf(plugin: AuthoringPlugin, path: String): UserSession? {
    val session = call.userSession
    if (session == null) {
        respondError("not authenticated", HttpStatusCode.Unauthorized)
        return null
    }
    if (!plugin.canEdit(session, path)) {
        respondError("not allowed to edit $path", HttpStatusCode.Forbidden)
        return null
    }
    return session
}

/** The store's history, or null having answered 404 — a store that keeps no past mounts no history. */
private suspend fun RoutingContext.versionedStore(plugin: AuthoringPlugin): Versioned? {
    val versioned = plugin.store as? Versioned
    if (versioned == null) respondError("this content store keeps no history", HttpStatusCode.NotFound)
    return versioned
}

private fun RoutingContext.limit() = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50

private suspend fun RoutingContext.respondJson(status: HttpStatusCode, block: Json.MutableObject.() -> Unit) {
    call.respondText(Json.MutableObject().apply(block).toString(), ContentType.Application.Json, status)
}

/** What the editor needs to open a block: its body, the rev to submit against, and who is holding it. */
private fun payload(plugin: AuthoringPlugin, path: String, block: Block?, lock: Locks.Lock?, session: UserSession) =
    Json.MutableObject().apply {
        set("body", block?.body ?: "")
        set("rev", block?.rev ?: "")
        set("meta", meta(block))
        set("lock", lock?.let { Json.MutableObject().apply { set("owner", it.owner); set("since", it.since) } })
        set("editable", plugin.canEdit(session, path))
    }

private fun meta(block: Block?) =
    Json.MutableObject().apply { block?.meta?.forEach { (key, value) -> set(key, value) } }

private fun revisions(log: List<Revision>) = Json.MutableArray().apply {
    log.forEach { revision ->
        push(Json.MutableObject().apply {
            set("rev", revision.rev)
            set("path", revision.path)
            set("author", revision.author)
            set("time", revision.time)
            revision.message?.let { set("message", it) }
        })
    }
}
