package com.republicate.kroom.plugins.analytics

import com.republicate.kroom.webapp.authoring.MemoryStorage
import com.republicate.kroom.webapp.authoring.installContentSite
import com.republicate.kroom.webapp.session.UserSession
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class AnalyticsTest {

    private val storage = MemoryStorage()

    private fun ApplicationTestBuilder.site() = application {
        installContentSite { this.storage = this@AnalyticsTest.storage; sessionSecret = "test-secret"; plugins += Analytics() }
        routing { get("/as/{who}") { call.sessions.set(UserSession(call.parameters["who"]!!, "x", null, "t")); call.respondText("ok") } }
    }

    @Test
    fun `nothing until a domain is set, then visitors are counted and authors are not`() = testApplication {
        site()
        assertFalse(client.get("/index").bodyAsText().contains("<script defer"))
        storage.settings("analytics")["domain"] = "example.org"
        assertContains(client.get("/index").bodyAsText(), """<script defer data-domain="example.org" src="https://plausible.io/js/script.js"></script>""")
        val author = createClient { install(HttpCookies) }.also { it.get("/as/admin") }
        assertFalse(author.get("/index").bodyAsText().contains("data-domain"))
    }
}
