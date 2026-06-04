package com.republicate.kroom.webapp.oauth

import kotlin.test.Test
import kotlin.test.assertEquals

class ReturnToValidationTest {

    @Test
    fun `null or blank falls back to root`() {
        assertEquals("/", validateReturnTo(null, "example.com", null))
        assertEquals("/", validateReturnTo("", "example.com", null))
    }

    @Test
    fun `relative path accepted`() =
        assertEquals("/foo?x=1", validateReturnTo("/foo?x=1", "example.com", null))

    @Test
    fun `protocol-relative rejected`() =
        assertEquals("/", validateReturnTo("//evil.com/x", "example.com", null))

    @Test
    fun `same host accepted`() =
        assertEquals("https://example.com/x", validateReturnTo("https://example.com/x", "example.com", null))

    @Test
    fun `foreign host rejected`() =
        assertEquals("/", validateReturnTo("https://evil.com/x", "example.com", null))

    @Test
    fun `subdomain of cookieDomain accepted`() = assertEquals(
        "https://bubble.republicate.com/x",
        validateReturnTo("https://bubble.republicate.com/x", "republicate.com", ".republicate.com")
    )

    @Test
    fun `apex of cookieDomain accepted`() = assertEquals(
        "https://republicate.com/x",
        validateReturnTo("https://republicate.com/x", "bubble.republicate.com", ".republicate.com")
    )

    @Test
    fun `suffix-but-not-subdomain rejected`() =
        assertEquals("/", validateReturnTo("https://evilrepublicate.com/x", "republicate.com", ".republicate.com"))

    @Test
    fun `non-http scheme rejected`() =
        assertEquals("/", validateReturnTo("javascript:alert(1)", "example.com", null))

    @Test
    fun `malformed uri rejected`() =
        assertEquals("/", validateReturnTo("ht tp://x", "example.com", null))
}
