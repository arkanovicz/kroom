package com.republicate.kroom.webapp.authoring

import com.republicate.kroom.webapp.session.UserSession
import com.republicate.kroom.webapp.session.sessionConfigOrNull
import com.republicate.kroom.webapp.session.validateReturnTo
import com.republicate.kroom.webapp.velocity.respondVelocity
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*

/**
 * A password login over the site's [IdentityProvider]: `POST /login` (`user`, `password`, `from`) and
 * `POST /logout`. The form is [page], served like any page — refused, it is rendered again with `$failed`.
 */
fun Route.loginRoutes(page: String) {
    val identity = application.authoring.identity

    post("/login") {
        val form = call.receiveParameters()
        val session = identity.authenticate(form["user"].orEmpty(), form["password"].orEmpty())
        if (session == null) return@post call.respondVelocity(page, mapOf("failed" to true, "from" to form["from"]))
        call.sessions.set(session)
        call.respondRedirect(validateReturnTo(form["from"], call.request.host(), application.sessionConfigOrNull?.cookieDomain))
    }

    post("/logout") {
        call.sessions.clear<UserSession>()
        call.respondRedirect(validateReturnTo(call.request.headers["Referer"], call.request.host(), application.sessionConfigOrNull?.cookieDomain))
    }
}
