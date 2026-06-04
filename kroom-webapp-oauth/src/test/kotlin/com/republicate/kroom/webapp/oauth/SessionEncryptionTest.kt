package com.republicate.kroom.webapp.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionEncryptionTest {

    // sealed format is {iv}/{encrypted}:{mac}; the mac only covers {encrypted}
    private fun flipChar(s: String, index: Int): String {
        val flipped = if (s[index] == '0') '1' else '0'
        return s.substring(0, index) + flipped + s.substring(index + 1)
    }

    @Test
    fun `round trip`() {
        val transformer = sessionTransformer("some-secret")
        val sealed = transformer.transformWrite("id=%23suser-123")
        assertEquals("id=%23suser-123", transformer.transformRead(sealed))
    }

    @Test
    fun `tampered ciphertext rejected`() {
        val transformer = sessionTransformer("some-secret")
        val sealed = transformer.transformWrite("payload")
        assertNull(transformer.transformRead(flipChar(sealed, sealed.indexOf('/') + 1)))
    }

    @Test
    fun `tampered mac rejected`() {
        val transformer = sessionTransformer("some-secret")
        val sealed = transformer.transformWrite("payload")
        assertNull(transformer.transformRead(flipChar(sealed, sealed.length - 1)))
    }

    @Test
    fun `wrong secret rejected`() {
        val sealed = sessionTransformer("secret-a").transformWrite("payload")
        assertNull(sessionTransformer("secret-b").transformRead(sealed))
    }
}
