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
 * POST   /shape/{path...}   render {page} to learn what the block sees: its first level, for completion
 * GET    /shape/{path...}?at=club.membres()[]   the level that path leads to
 * GET    /history/{path...} revisions of one block   ] 404 unless the store is Versioned
 * GET    /journal           the site-wide log        ]
 * ```
 *
 * The lock routes read `/lock/…` rather than `…/lock`: a ktor tailcard takes every remaining segment, so
 * nothing can follow `{path...}` in a route pattern.
 *
 * An error answers a `code` (and its `args`) beside its English `message`: the client's strings table is
 * keyed by those codes, so an application translates the server's words where it translates the editor's.
 *
 * A submit carries the rev the author started from, so a block edited meanwhile is answered with *theirs*
 * instead of being overwritten — the lock is the polite path, the rev check is the safe one.
 */
/** The context key `#markdown` reads a draft from (kroom-markdown's `MarkdownMacro.DRAFTS`). */
private const val DRAFTS = "kroomDrafts"

/** The context key `#markdown` answers what a block sees in (kroom-markdown's `MarkdownMacro.SHAPES`). */
private const val SHAPES = "kroomShapes"

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
                return@post notYourLock(path)

            val asked = receiveJsonObject()
            val html = renderDraft(asked, path) ?: return@post
            respondJson { set("page", html) }
        }

        // Completion: what a block sees only exists in its page's render, so the page renders once, then each
        // `.` walks the types its lock remembers — no second render, and no value kept past the request.
        route("/shape/{path...}") {
            post {
                val path = blockPath() ?: return@post
                val session = editorOf(plugin, path) ?: return@post
                if (!plugin.locks.touch(path, session.id))
                    return@post notYourLock(path)

                val asked = mutableMapOf<String, Any?>(path to null)
                renderPage(receiveJsonObject().getString("page"), mapOf(SHAPES to asked)) ?: return@post
                @Suppress("UNCHECKED_CAST")
                val shape = asked[path] as? Shape
                    ?: return@post respondError("$path is not on this page", HttpStatusCode.NotFound, "notOnPage", mapOf("path" to path))
                if (!plugin.locks.attach(path, session.id, shape))
                    return@post notYourLock(path)
                respondJson(Json.toJson(shape(emptyList()))!!)
            }

            get {
                val path = blockPath() ?: return@get
                val session = editorOf(plugin, path) ?: return@get
                if (!plugin.locks.touch(path, session.id))
                    return@get notYourLock(path)
                val shape = plugin.locks.holder(path)?.shape
                    ?: return@get respondError("no shape for $path yet", HttpStatusCode.NotFound, "noShape", mapOf("path" to path))
                val at = call.request.queryParameters["at"].orEmpty()
                val level = shape(steps(at))
                    ?: return@get respondError("nothing at $at", HttpStatusCode.NotFound, "noSuchMember", mapOf("at" to at))
                respondJson(Json.toJson(level)!!)
            }
        }

        route("/lock/{path...}") {
            post {
                val path = blockPath() ?: return@post
                val session = editorOf(plugin, path) ?: return@post
                val lock = plugin.locks.acquire(path, session.id)
                    ?: return@post plugin.locks.holder(path)?.owner.let { owner ->
                        respondError("block held by $owner", HttpStatusCode.Conflict, "lockHeld", mapOf("owner" to owner))
                    }
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
                    ?: return@get respondError("no such revision: $asked", HttpStatusCode.NotFound, "noSuchRevision", mapOf("rev" to asked))
            }
            respondJson(payload(plugin, path, block, plugin.locks.holder(path), session))
        }

        // trash: the block goes, a region it overrode falls back to the site's default; versioned stores keep it
        delete("{path...}") {
            val path = blockPath() ?: return@delete
            val session = editorOf(plugin, path) ?: return@delete
            plugin.locks.holder(path)?.takeIf { it.owner != session.id }?.let { return@delete notYourLock(path) }
            if (!plugin.store.delete(path))
                return@delete respondError("no block at $path", HttpStatusCode.NotFound, "noBlock", mapOf("path" to path))
            plugin.locks.release(path, session.id)
            respondSuccess()
        }

        post("{path...}") {
            val path = blockPath() ?: return@post
            val session = editorOf(plugin, path) ?: return@post
            // the same call refreshes the lock and proves it is his — a submit without it is a lost edit
            if (!plugin.locks.touch(path, session.id))
                return@post notYourLock(path)

            val submitted = receiveJsonObject()
            // what would break the page once published is refused now, while its author is still there
            // (no velocity, no page to break: the edit API stands on its own)
            if (application.velocityOrNull != null) renderDraft(submitted, path) ?: return@post
            val theirs = plugin.store.read(path)
            if ((submitted.getString("rev") ?: "") != (theirs?.rev ?: "")) {
                return@post respondJson(HttpStatusCode.Conflict) {
                    set("message", "$path changed since you started editing")
                    set("code", "stale")
                    set("args", Json.MutableObject().apply { set("path", path) })
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
            plugin.published(written, session)
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
private suspend fun RoutingContext.renderDraft(asked: Json.Object, path: String): String? =
    renderPage(asked.getString("page"), mapOf(DRAFTS to mapOf(path to (asked.getString("body") ?: ""))))

/**
 * The page at [page], rendered for this call with [extra] in its context — or null having answered: 400/404
 * for a missing or unknown page, 422 with the author's problem when the render breaks.
 */
private suspend fun RoutingContext.renderPage(page: String?, extra: Map<String, Any?>): String? {
    if (page == null) {
        respondError("a block is rendered within the page it is in: page missing", code = "pageMissing")
        return null
    }
    val (template, bound) = call.application.authoring.pageResolver?.invoke(call, page) ?: call.application.resolvePage(page) ?: run {
        respondError("no page at $page", HttpStatusCode.NotFound, "noPage", mapOf("page" to page))
        return null
    }
    return try {
        call.application.velocity.renderForCall(call, template, bound + extra)
    } catch (e: Exception) {
        // the engine's wrappers say where it surfaced; the innermost cause says what the author wrote wrong
        val cause = generateSequence<Throwable>(e) { it.cause }.last { it.message != null }
        respondError(cause.message!!, HttpStatusCode.UnprocessableEntity, "broken", mapOf("message" to cause.message))
        null
    }
}

/** `club.membres()[]` → `club`, `membres()`, `[]`: a member's key holds no dot, an iteration is a trailing `[]`. */
private fun steps(at: String): List<String> = at.split('.').filter { it.isNotEmpty() }.flatMap { segment ->
    val name = segment.trimEnd('[', ']')
    listOfNotNull(name.ifEmpty { null }) + List((segment.length - name.length) / 2) { "[]" }
}

/** The block path, or null having answered 400 — an empty or traversing path never reaches a store. */
private suspend fun RoutingContext.blockPath(): String? {
    val segments = call.parameters.getAll("path").orEmpty().filter { it.isNotEmpty() }
    if (segments.isEmpty() || segments.any { it == ".." }) {
        respondError("invalid content path", code = "invalidPath")
        return null
    }
    return segments.joinToString("/")
}

/** The caller if he may edit [path], or null having answered 401/403. */
private suspend fun RoutingContext.editorOf(plugin: AuthoringPlugin, path: String): UserSession? {
    val session = call.userSession
    if (session == null) {
        respondError("not authenticated", HttpStatusCode.Unauthorized, "notAuthenticated")
        return null
    }
    if (!plugin.canEdit(session, path)) {
        respondError("not allowed to edit $path", HttpStatusCode.Forbidden, "forbidden", mapOf("path" to path))
        return null
    }
    return session
}

/** The store's history, or null having answered 404 — a store that keeps no past mounts no history. */
private suspend fun RoutingContext.versionedStore(plugin: AuthoringPlugin): Versioned? {
    val versioned = plugin.store as? Versioned
    if (versioned == null) respondError("this content store keeps no history", HttpStatusCode.NotFound, "noHistory")
    return versioned
}

private suspend fun RoutingContext.notYourLock(path: String) =
    respondError("the lock on $path is not yours", HttpStatusCode.Conflict, "notYourLock", mapOf("path" to path))

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
