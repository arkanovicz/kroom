package com.republicate.kroom.webapp.auth

import com.republicate.kroom.webapp.core.receiveJsonObject
import com.republicate.kroom.webapp.core.respondError
import com.republicate.kroom.webapp.core.respondJson
import com.republicate.kroom.webapp.session.UserSession
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*

private const val MIN_PASSWORD_LENGTH = 8

/** Email+password routes: POST /api/auth/{register,login,logout}. */
fun <ID> Route.authRoutes(store: AuthStore<ID>, hasher: Argon2Hasher) {
    route("/api/auth") {

        post("/register") {
            val body = receiveJsonObject()
            val emailRaw = body.getString("email")?.trim()
            val password = body.getString("password")
            val displayName = body.getString("displayName")?.trim()
            if (emailRaw.isNullOrBlank() || emailRaw.indexOf('@') <= 0)
                return@post respondError("invalid email")
            if (password == null || password.length < MIN_PASSWORD_LENGTH)
                return@post respondError("password must be at least $MIN_PASSWORD_LENGTH characters")
            if (displayName.isNullOrBlank())
                return@post respondError("displayName required")

            val email = normalizeEmail(emailRaw)
            if (store.findByNormalizedEmail(email) != null)
                return@post respondError("email already registered", HttpStatusCode.Conflict)

            val principal = try {
                store.createPrincipal(email, displayName)
            } catch (e: AuthStoreException) {
                return@post respondError(e.message ?: "registration rejected", HttpStatusCode.Conflict)
            }
            store.createCredential(principal.id, "password", hasher.hash(password), null)
            call.setUserSession(principal, "password")
            respondAuthenticated(principal)
        }

        post("/login") {
            val body = receiveJsonObject()
            val emailRaw = body.getString("email")?.trim()
            val password = body.getString("password")
            if (emailRaw.isNullOrBlank() || password == null)
                return@post respondError("invalid credentials", HttpStatusCode.Unauthorized)

            val principal = store.findByNormalizedEmail(normalizeEmail(emailRaw))
            val hash = principal?.let { store.findCredential(it.id, "password") }?.passwordHash
            if (principal == null || hash == null || !hasher.verify(password, hash))
                return@post respondError("invalid credentials", HttpStatusCode.Unauthorized)

            store.touch(principal.id)
            call.setUserSession(principal, "password")
            respondAuthenticated(principal)
        }

        post("/logout") {
            call.sessions.clear<UserSession>()
            respondJson { set("authenticated", false) }
        }
    }
}

private fun <ID> ApplicationCall.setUserSession(principal: Principal<ID>, provider: String) {
    sessions.set(
        UserSession(
            id = principal.id.toString(),
            name = principal.displayName,
            email = principal.email,
            provider = provider
        )
    )
}

private suspend fun <ID> RoutingContext.respondAuthenticated(principal: Principal<ID>) {
    respondJson {
        set("authenticated", true)
        set("id", principal.id.toString())
        set("name", principal.displayName)
    }
}
