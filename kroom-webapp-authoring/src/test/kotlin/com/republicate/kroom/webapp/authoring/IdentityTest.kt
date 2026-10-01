package com.republicate.kroom.webapp.authoring

import com.republicate.kroom.webapp.session.UserSession
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The provider says who holds which role; the site's table says what a role may do. */
class IdentityTest {

    @Test
    fun `a role grants exact names, a dotted wildcard, or everything`() {
        val roles = Roles().apply { grant("moderator", "forms.read", "comments.*") }
        assertTrue(roles.can(setOf(Roles.ADMIN), "anything.at-all", ""))
        assertTrue(roles.can(setOf(Roles.EDITOR), Permissions.EDIT, "pages/x.md"))
        assertFalse(roles.can(setOf(Roles.EDITOR), Permissions.ADMIN, ""))
        assertTrue(roles.can(setOf(Roles.EDITOR), Permissions.PAGE_EDIT, "/x") && roles.can(setOf(Roles.EDITOR), Permissions.MENU_EDIT, ""))
        assertTrue(roles.can(setOf(Roles.AUTHOR), Permissions.EDIT, "pages/x.md"))
        assertFalse(roles.can(setOf(Roles.AUTHOR), Permissions.PAGE_EDIT, "/x") || roles.can(setOf(Roles.AUTHOR), Permissions.UPLOAD, ""))
        assertTrue(roles.can(setOf("moderator"), "comments.delete", ""))
        assertFalse(roles.can(setOf("moderator"), "forms.delete", ""))
        assertFalse(roles.can(setOf("moderator"), "commentsX.read", ""))  // `comments.*` is not a string prefix
    }

    @Test
    fun `the memory provider authenticates and answers roles`() = runBlocking {
        val users = MemoryIdentityProvider().user("admin", "secret", Roles.ADMIN)
        assertNull(users.authenticate("admin", "wrong"))
        val session = users.authenticate("admin", "secret")!!
        assertEquals(setOf(Roles.ADMIN), users.roles(session))
        assertEquals(emptySet(), users.roles(UserSession("stranger", "stranger", null, "oidc")))
    }

    @Test
    fun `the login page authenticates against the provider`() = testApplication {
        application {
            installContentSite {
                sessionSecret = "test-secret"
                identity = MemoryIdentityProvider().user("admin", "admin", Roles.ADMIN)
                loginPage = "pages/login.html"
            }
        }
        val browser = createClient { install(HttpCookies); followRedirects = false }
        val refused = browser.submitForm("/login", parameters { append("user", "admin"); append("password", "nope") })
        assertContains(refused.bodyAsText(), "admin / admin")          // the page again, with `$failed`
        val accepted = browser.submitForm("/login", parameters { append("user", "admin"); append("password", "admin"); append("from", "/club/13Ma") })
        assertEquals(HttpStatusCode.Found, accepted.status)
        assertEquals("/club/13Ma", accepted.headers[HttpHeaders.Location])
        assertContains(browser.get("/club/13Ma").bodyAsText(), "kroom-edit")
    }
}
