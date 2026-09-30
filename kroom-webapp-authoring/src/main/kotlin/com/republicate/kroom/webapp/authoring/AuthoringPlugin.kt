package com.republicate.kroom.webapp.authoring

import com.republicate.kroom.webapp.session.UserSession
import com.republicate.kroom.webapp.session.userSession
import com.republicate.kroom.webapp.velocity.velocityOrNull
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * In-place editing of a [ResourceStore]: the block a visitor reads is the block an author edits, through
 * the same tree. This plugin owns the edit API and the block locks; the page side — the wrapper template,
 * the client script — is the application's, and only needs to know where to post.
 *
 * Install after `installSessions { }` (the author's identity) and, when pages render blocks, after
 * `installVelocity { }` — the scope values below are registered only if velocity is already there.
 */
class AuthoringConfig {
    /** The content tree read and written. Required. */
    var store: ResourceStore? = null

    /** A lock untouched for this long is free again: a closed tab must not hold a block hostage. */
    var lockTimeout: Duration = 2.minutes

    /** Where the edit API mounts. */
    var apiPrefix: String = "/api/content"

    /** Who is who, and their roles. The default knows nobody: nothing is editable until one is given. */
    var identity: IdentityProvider = MemoryIdentityProvider()

    /** What each role may do; editing a block asks for [Permissions.EDIT] on its path. */
    var roles: Roles = Roles()

    /** Uploaded files, served under [mediaPrefix]; null mounts no media route. */
    var media: Media? = null

    /** Where media is served — public: a page shows it to anyone. */
    var mediaPrefix: String = "/media"

    /** The largest upload accepted, in bytes. */
    var mediaMaxSize: Long = 10L * 1024 * 1024

    /** Told of each submitted block once it is written (plugins' `onPublish`); it must not throw. */
    var published: suspend (Block, UserSession) -> Unit = { _, _ -> }

    /** What a page shows in place of a block nobody has written yet. */
    var placeholder: String? = null

    /**
     * The language the editor (and the admin bar) speaks: one kroom stocks (`en`, `fr`), or `auto` — the
     * browser's first stocked one, English otherwise.
     */
    var language: String = "auto"
}

class AuthoringPlugin(private val config: AuthoringConfig) {

    val store: ResourceStore = requireNotNull(config.store) { "installAuthoring { store = … } is required" }

    val locks = Locks(config.lockTimeout)

    val apiPrefix: String get() = config.apiPrefix
    val media: Media? get() = config.media
    val mediaPrefix: String get() = config.mediaPrefix
    internal val mediaMaxSize: Long get() = config.mediaMaxSize
    internal val published get() = config.published
    val placeholder: String? get() = config.placeholder

    /** The editor's script, stylesheet and language, for a layout to emit — `$authoring.assets.tags()`. */
    val assets = AuthoringAssets(config.language)

    val identity: IdentityProvider get() = config.identity
    val roles: Roles get() = config.roles

    fun roles(session: UserSession?): Set<String> = session?.let { identity.roles(it) }.orEmpty()

    /** Whether [session] (null: a visitor) may [permission] on [target] — "" when site-wide. */
    fun can(session: UserSession?, permission: String, target: String = ""): Boolean =
        session != null && roles.can(roles(session), permission, target)

    /** The path is "" for the site-wide journal. */
    fun canEdit(session: UserSession?, path: String): Boolean = can(session, Permissions.EDIT, path)
}

private val AuthoringKey = AttributeKey<AuthoringPlugin>("KroomAuthoring")

val Application.authoring: AuthoringPlugin
    get() = attributes[AuthoringKey]

/** Authoring plugin if installed, else null — for optional cross-module wiring. */
val Application.authoringOrNull: AuthoringPlugin?
    get() = attributes.getOrNull(AuthoringKey)

/**
 * Install the edit API and mount it under [AuthoringConfig.apiPrefix].
 *
 * The default block wrapper ships here as `kroom/block-wrapper.html` — at the classpath root, so every
 * engine shape resolves it — but authoring cannot select it: velocity is installed first and its engine
 * is already built, so the application names it itself, in `installVelocity`:
 *
 *     properties["markdown.block.wrapper"] = "kroom/block-wrapper.html"
 */
fun Application.installAuthoring(block: AuthoringConfig.() -> Unit = {}) {
    val plugin = AuthoringPlugin(AuthoringConfig().apply(block))
    attributes.put(AuthoringKey, plugin)

    // a page renders its own editing chrome: who is logged in ($logged.name), where to post ($authoring.apiPrefix)
    velocityOrNull?.let { velocity ->
        velocity.registerSession("logged") { it.userSession }
        velocity.registerApplication("authoring") { plugin }
    }

    routing {
        authoringRoutes()
        if (plugin.media != null) mediaRoutes()
    }
}
