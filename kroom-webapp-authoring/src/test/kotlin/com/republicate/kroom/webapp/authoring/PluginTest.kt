package com.republicate.kroom.webapp.authoring

import com.republicate.kroom.webapp.session.UserSession
import io.ktor.client.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Every registration a plugin makes lands where the site needs it, and nowhere a visitor could reach. */
class PluginTest {

    private val published = CompletableDeferred<String>()
    private val missed = java.util.concurrent.CopyOnWriteArrayList<String>()

    private val probe = object : Plugin {
        override val id = "probe"
        override val settings = listOf(Setting.text("greeting", default = "hello"), Setting.secret("token"))
        override fun install(site: Site) {
            site.grant(Roles.EDITOR, "probe.read")
            site.tool("probe", Greeter(site, this), blocks = true)
            site.head { """<meta name="probe" content="${site.settings(this)["greeting"]}">""" }
            site.foot { "<!-- probe foot -->" }
            site.admin(AdminEntry("probe", "Probe", tables = listOf(AdminTable("rows", "Rows", "/api/probe/rows")), permission = "probe.read"))
            site.routes { get("/api/probe/rows") { call.respondText("""{"columns":["a"],"rows":[["1"]]}""", ContentType.Application.Json) } }
            site.intercept { call -> if (call.request.local.uri == "/old") call.respondRedirect("/club/13Ma", permanent = true) }
            site.notFound { call -> missed += call.request.local.uri }
            site.notFound { call -> if (call.request.local.uri == "/late") call.respondRedirect("/club/13Ma") }
            site.onPublish { block, _ -> published.complete(block.path) }
        }
    }

    /** Paired with a service: nothing to enable until its address is set. */
    private val needy = object : Plugin {
        override val id = "needy"
        override val settings = listOf(Setting.text("url", "Service"))
        override fun install(site: Site) {}
        override fun check(settings: Settings) = if (settings["url"].isNullOrBlank()) "no service address" else null
    }

    class Greeter(private val site: Site, private val plugin: Plugin) {
        fun greet(name: String) = "${site.settings(plugin)["greeting"]}, $name"
    }

    private fun ApplicationTestBuilder.site() = application {
        installContentSite {
            sessionSecret = "test-secret"
            identity = IdentityProvider { mapOf("admin" to setOf(Roles.ADMIN), "editor" to setOf(Roles.EDITOR))[it.id].orEmpty() }
            plugins += probe
            plugins += needy
            strings["probe.name"] = "Sonde"
        }
        routing {
            get("/as/{who}") { call.sessions.set(UserSession(call.parameters["who"]!!, "x", null, "test")); call.respondText("ok") }
        }
    }

    private suspend fun ApplicationTestBuilder.visitor(name: String? = null): HttpClient =
        createClient { install(HttpCookies); followRedirects = false }.also { if (name != null) it.get("/as/$name") }

    @Test
    fun `head and foot reach the layout, the admin bar only an admin`() = testApplication {
        site()
        val anonymous = visitor().get("/club/13Ma").bodyAsText()
        assertContains(anonymous, """<meta name="probe" content="hello">""")
        assertContains(anonymous, "<!-- probe foot -->")
        assertFalse(anonymous.contains("kroom-admin"))
        assertFalse(visitor("editor").get("/club/13Ma").bodyAsText().contains("kroom-admin"))

        val admin = visitor("admin").get("/club/13Ma").bodyAsText()
        assertContains(admin, """<aside class="kroom-admin"""")
        assertContains(admin, "&quot;id&quot;:&quot;probe&quot;")      // the plugin's entry, as data
        assertContains(admin, "/js/admin.js?v=")
        // the plugin's words, as the application translates them, reach the bar
        assertContains(admin, """Object.assign((window.kroomAdmin.strings ??= {}), {"probe.name":"Sonde"});""")
    }

    @Test
    fun `a block calls a block tool, with the settings an admin set`() = testApplication {
        site()
        val admin = visitor("admin")
        admin.put("/api/site/plugins/probe/settings") { contentType(ContentType.Application.Json); setBody("""{"greeting":"salut"}""") }
            .let { assertEquals(HttpStatusCode.OK, it.status) }
        admin.post("/api/content/lock/pages/club/13Ma/agenda.md")
        val preview = admin.post("/api/content/preview/pages/club/13Ma/agenda.md") {
            contentType(ContentType.Application.Json)
            setBody("""{"page":"/club/13Ma","body":"${'$'}probe.greet('Alice')"}""")
        }.bodyAsText()
        assertContains(preview, "salut, Alice")
    }

    @Test
    fun `settings are an admin's, and a secret is never read back`() = testApplication {
        site()
        val admin = visitor("admin")
        admin.put("/api/site/plugins/probe/settings") { contentType(ContentType.Application.Json); setBody("""{"token":"s3cr3t"}""") }
        val listed = admin.get("/api/site/plugins").bodyAsText()
        assertFalse(listed.contains("s3cr3t"))
        assertContains(listed, """"set":true""")
        assertEquals(HttpStatusCode.Forbidden, visitor("editor").get("/api/site/plugins").status)
        assertEquals(HttpStatusCode.BadRequest,
            admin.put("/api/site/plugins/probe/settings") { contentType(ContentType.Application.Json); setBody("""{"nope":"1"}""") }.status)
    }

    @Test
    fun `an interceptor answers before routing, a publish reaches the listeners`() = testApplication {
        site()
        val moved = visitor().get("/old")
        assertEquals(HttpStatusCode.MovedPermanently, moved.status)

        val admin = visitor("admin")
        admin.post("/api/content/lock/pages/club/13Ma/agenda.md")
        admin.post("/api/content/pages/club/13Ma/agenda.md") {
            contentType(ContentType.Application.Json)
            setBody("""{"page":"/club/13Ma","rev":"","body":"Mardi."}""")
        }.let { assertEquals(HttpStatusCode.OK, it.status) }
        assertEquals("pages/club/13Ma/agenda.md", withTimeout(5000) { published.await() })
    }

    @Test
    fun `what nothing answers is seen by notFound handlers, which may still answer it`() = testApplication {
        site()
        assertEquals(HttpStatusCode.NotFound, client.get("/nowhere").status)
        assertEquals(HttpStatusCode.Found, visitor().get("/late").status)
        assertEquals(HttpStatusCode.OK, client.get("/club/13Ma").status)
        assertEquals(listOf("/nowhere", "/late"), missed)
    }

    @Test
    fun `the pages panel lists routes, and the pages their blocks say exist`() = testApplication {
        site()
        val admin = visitor("admin")
        admin.post("/api/content/lock/pages/club/22Ly/agenda.md")
        admin.post("/api/content/pages/club/22Ly/agenda.md") {
            contentType(ContentType.Application.Json)
            setBody("""{"page":"/club/22Ly","rev":"","body":"Jeudi."}""")
        }
        val pages = admin.get("/api/site/pages").bodyAsText()
        assertContains(pages, """"route":"/club/{club}"""")
        assertContains(pages, """"urls":["/club/22Ly"]""")
        assertContains(pages, """"route":"/login"""")
    }

    @Test
    fun `a disabled plugin falls silent everywhere, and comes back when enabled`() = testApplication {
        site()
        val admin = visitor("admin")
        val off = admin.put("/api/site/plugins/probe/enabled") { contentType(ContentType.Application.Json); setBody("""{"enabled":false}""") }
        assertEquals(HttpStatusCode.OK, off.status, off.bodyAsText())
        assertContains(admin.get("/api/site/plugins").bodyAsText(), """"id":"probe","name":"probe","description":"","enabled":false""")

        val page = admin.get("/club/13Ma").bodyAsText()
        assertFalse(page.contains("<meta name=\"probe\""), "its head fragment")
        assertFalse(page.contains("probe foot"), "its foot fragment")
        assertFalse(page.contains("&quot;id&quot;:&quot;probe&quot;"), "its admin entry")
        assertEquals(HttpStatusCode.NotFound, client.get("/api/probe/rows").status, "its routes")
        assertEquals(HttpStatusCode.NotFound, visitor().get("/old").status, "its interceptor")
        visitor().get("/late").let { assertEquals(HttpStatusCode.NotFound, it.status, "its notFound handler") }
        assertEquals(emptyList(), missed, "its other notFound handler")
        admin.post("/api/content/lock/pages/club/13Ma/agenda.md")
        val preview = admin.post("/api/content/preview/pages/club/13Ma/agenda.md") {
            contentType(ContentType.Application.Json)
            setBody("""{"page":"/club/13Ma","body":"${'$'}probe.greet('Alice')"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, preview.status, "its block tool: a block calling it is refused")

        admin.put("/api/site/plugins/probe/enabled") { contentType(ContentType.Application.Json); setBody("""{"enabled":true}""") }
            .let { assertEquals(HttpStatusCode.OK, it.status) }
        assertContains(admin.get("/club/13Ma").bodyAsText(), """<meta name="probe" content="hello">""")
        assertEquals(HttpStatusCode.OK, client.get("/api/probe/rows").status)
    }

    @Test
    fun `a plugin paired with a service stays off until the service is there`() = testApplication {
        site()
        val admin = visitor("admin")
        admin.put("/api/site/plugins/needy/enabled") { contentType(ContentType.Application.Json); setBody("""{"enabled":false}""") }
        val refused = admin.put("/api/site/plugins/needy/enabled") { contentType(ContentType.Application.Json); setBody("""{"enabled":true}""") }
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertContains(refused.bodyAsText(), """"code":"pluginCheck"""")
        assertContains(refused.bodyAsText(), "no service address")
        assertContains(admin.get("/api/site/plugins").bodyAsText(), """"id":"needy","name":"needy","description":"","enabled":false""")
        admin.put("/api/site/plugins/needy/settings") { contentType(ContentType.Application.Json); setBody("""{"url":"http://solr:8983"}""") }
        admin.put("/api/site/plugins/needy/enabled") { contentType(ContentType.Application.Json); setBody("""{"enabled":true}""") }
            .let { assertEquals(HttpStatusCode.OK, it.status) }
    }
}
