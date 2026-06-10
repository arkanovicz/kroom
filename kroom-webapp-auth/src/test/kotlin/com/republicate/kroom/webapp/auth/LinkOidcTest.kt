package com.republicate.kroom.webapp.auth

import com.republicate.kroom.webapp.session.UserSession
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LinkOidcTest {

    private fun oidc(email: String?, sub: String = "google-sub", name: String = "Alice") =
        UserSession(id = sub, name = name, email = email, provider = "google")

    @Test
    fun `first sight creates principal and provider credential, re-anchoring the session id`() = runBlocking {
        val store = InMemoryAuthStore()
        val linked = store.linkOidc(oidc("Alice+x@Example.com"))!!

        assertEquals("1", linked.id)                 // local principal id, not the OIDC subject
        assertEquals("alice+x@example.com", linked.email) // normalized, +tag kept
        val cred = store.findCredential(1, "google")
        assertNotNull(cred)
        assertEquals("google-sub", cred.oauthId)     // subject stored on the credential
    }

    @Test
    fun `returning user links to the same principal, no duplicate credential`() = runBlocking {
        val store = InMemoryAuthStore()
        val first = store.linkOidc(oidc("alice@example.com"))!!
        val second = store.linkOidc(oidc("alice@example.com"))!!
        assertEquals(first.id, second.id)
    }

    @Test
    fun `+tag variants are distinct principals`() = runBlocking {
        val store = InMemoryAuthStore()
        val a = store.linkOidc(oidc("alice+a@example.com"))!!
        val b = store.linkOidc(oidc("alice+b@example.com"))!!
        assertEquals("1", a.id)
        assertEquals("2", b.id)
    }

    @Test
    fun `a provider identity without email is rejected`() = runBlocking {
        assertNull(InMemoryAuthStore().linkOidc(oidc(null)))
    }
}
