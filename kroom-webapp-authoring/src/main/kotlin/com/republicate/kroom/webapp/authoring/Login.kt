package com.republicate.kroom.webapp.authoring

import com.republicate.kroom.webapp.session.UserSession
import com.republicate.kroom.webapp.session.sessionConfigOrNull
import com.republicate.kroom.webapp.session.validateReturnTo
import com.republicate.kroom.webapp.velocity.respondVelocity
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*

/** The page one was on when the login page was asked for, kept by the site while one logs in. */
private const val FROM_COOKIE = "kroom_from"

/**
 * Serving its login page, the site remembers where one came from (the Referer, this host, not the login page
 * itself) — nothing to carry in the address or the form; [loginRoutes] goes back there.
 */
internal fun Site.rememberWhereFrom() = intercept { call ->
    val route = loginRoute ?: return@intercept
    if (call.request.httpMethod != HttpMethod.Get || call.request.path() != route) return@intercept
    val referer = call.request.headers[HttpHeaders.Referrer]?.let { runCatching { Url(it) }.getOrNull() } ?: return@intercept
    if (referer.host != call.request.host() || referer.encodedPath == route) return@intercept
    val from = referer.encodedPath + referer.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
    call.response.cookies.append(FROM_COOKIE, from, path = "/", httpOnly = true, maxAge = 600)
}

/**
 * A password login over the site's [IdentityProvider]: `POST /login` (`user`, `password`) and `POST /logout`.
 * The form is [page], served like any page — refused, it is rendered again with `$failed`. Logged in, one is
 * back where one was ([rememberWhereFrom]) — or where a `from` field says, for an application that sets one.
 */
fun Route.loginRoutes(page: String) {
    val identity = application.authoring.identity

    post("/login") {
        val form = call.receiveParameters()
        val from = form["from"]?.takeIf { it.isNotBlank() } ?: call.request.cookies[FROM_COOKIE]
        val session = identity.authenticate(form["user"].orEmpty(), form["password"].orEmpty())
        if (session == null) return@post call.respondVelocity(page, mapOf("failed" to true))
        call.sessions.set(session)
        call.response.cookies.append(FROM_COOKIE, "", path = "/", maxAge = 0)
        call.respondRedirect(validateReturnTo(from, call.request.host(), application.sessionConfigOrNull?.cookieDomain))
    }

    post("/logout") {
        call.sessions.clear<UserSession>()
        call.respondRedirect(validateReturnTo(call.request.headers["Referer"], call.request.host(), application.sessionConfigOrNull?.cookieDomain))
    }
}
