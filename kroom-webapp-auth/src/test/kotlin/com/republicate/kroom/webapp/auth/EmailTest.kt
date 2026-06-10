package com.republicate.kroom.webapp.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class EmailTest {

    @Test
    fun `normalize lowercases and trims`() =
        assertEquals("alice@example.com", normalizeEmail("  Alice@Example.COM "))

    @Test
    fun `normalize keeps the +tag (variants are distinct identities)`() {
        assertEquals("alice+work@example.com", normalizeEmail("Alice+Work@example.com"))
        assertEquals("alice+a@x.com", normalizeEmail("alice+a@x.com"))
        assertEquals("alice+b@x.com", normalizeEmail("alice+b@x.com"))
    }

    @Test
    fun `base strips the +tag for quota grouping`() {
        assertEquals("alice@example.com", emailBase("alice+work@example.com"))
        assertEquals("alice@example.com", emailBase("Alice@Example.com"))
        assertEquals("alice@x.com", emailBase("alice+a@x.com"))
        assertEquals("alice@x.com", emailBase("alice+b@x.com"))
    }

    @Test
    fun `base tolerates malformed input`() {
        assertEquals("not-an-email", emailBase("not-an-email"))
        assertEquals("@x.com", emailBase("@x.com"))
    }
}
