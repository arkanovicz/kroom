package com.republicate.kroom.webapp.authoring

import com.republicate.kroom.webapp.session.UserSession
import java.util.concurrent.ConcurrentHashMap

/**
 * Who is who. The provider knows people — it turns credentials into a session, and a session into its
 * roles — and nothing about what a role may do: that is [Roles], the site's own table, which plugins extend
 * with the permissions they bring. So an LDAP directory, an OIDC server or a users table swap in without
 * touching a single permission, and a plugin adds `forms.read` without knowing where users come from.
 *
 * Roles are asked per call, never cached in the session cookie: a role taken away is gone at the next click.
 */
fun interface IdentityProvider {

    /** The roles [session] holds right now — none for someone the provider does not know. */
    fun roles(session: UserSession): Set<String>

    /**
     * A password-shaped login: the session to open, or null. A provider whose login is a redirect (OIDC,
     * `kroom-webapp-oauth`) answers null here and lands its session through its own routes.
     */
    suspend fun authenticate(login: String, password: String): UserSession? = null
}

/**
 * What each role may do: permission patterns per role — `content.edit`, `forms.*`, `*`. [can] is open, for
 * the rights a flat table cannot say (per-club editors: `editor@13Ma` may edit under `pages/club/13Ma/`).
 */
open class Roles {

    companion object {
        const val ADMIN = "admin"
        const val EDITOR = "editor"
    }

    private val grants = ConcurrentHashMap<String, MutableSet<String>>()

    init {
        grant(ADMIN, "*")
        grant(EDITOR, "${Permissions.CONTENT}.*")
    }

    fun grant(role: String, vararg permissions: String) {
        grants.computeIfAbsent(role) { ConcurrentHashMap.newKeySet() }.addAll(permissions)
    }

    fun revoke(role: String, vararg permissions: String) {
        grants[role]?.removeAll(permissions.toSet())
    }

    fun grants(role: String): Set<String> = grants[role].orEmpty().toSortedSet()

    val all: Map<String, Set<String>> get() = grants.keys.sorted().associateWith { grants(it) }

    /** Whether any of [roles] may [permission] on [target] ("" when site-wide). */
    open fun can(roles: Set<String>, permission: String, target: String): Boolean =
        roles.any { role -> grants[role].orEmpty().any { matches(it, permission) } }

    private fun matches(pattern: String, permission: String) =
        pattern == "*" || pattern == permission ||
            pattern.endsWith(".*") && permission.startsWith(pattern.removeSuffix("*"))
}

/** The permissions kroom itself asks about; a plugin's are named after its id (`forms.read`). */
object Permissions {
    const val CONTENT = "content"
    /** Write a block — target: its path. */
    const val EDIT = "content.edit"
    /** Add a file to the media. */
    const val UPLOAD = "content.upload"
    /** The admin bar, the plugin list, every plugin's settings. */
    const val ADMIN = "site.admin"
}

/**
 * The demo's directory: users and their roles in memory, passwords in the clear. Enough to click through a
 * site; a real one is kroom-webapp-auth's store, an LDAP bind, an OIDC server's claims.
 */
class MemoryIdentityProvider : IdentityProvider {

    private class User(val password: String, val name: String, val roles: Set<String>)

    private val users = ConcurrentHashMap<String, User>()

    fun user(login: String, password: String, vararg roles: String, name: String = login) = apply {
        users[login] = User(password, name, roles.toSet())
    }

    override fun roles(session: UserSession): Set<String> = users[session.id]?.roles.orEmpty()

    override suspend fun authenticate(login: String, password: String): UserSession? =
        users[login]?.takeIf { it.password == password }?.let { UserSession(login, it.name, null, "memory") }
}
