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
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A page hands its parts down as `#define`s and names its layout; the active theme lays them out, straight to
 * the response. Which theme is active is the site's setting — an admin previews another without changing it.
 */
/** A skin of one stylesheet, with a region partial of its own (test resources) and a head fragment. */
class Skinny : Skin {
    override val id = "skinny"
    override val stylesheets = listOf("/css/skinny.css")
    override fun install(site: Site) { site.head { """<meta name="skinny" content="on">""" } }
}

class ThemeTest {

    private object Plain : Theme {
        override val id = "plain"
        override val layouts = setOf("default")
    }

    private val storage = MemoryStorage()

    private fun ApplicationTestBuilder.site() = application {
        installContentSite {
            this.storage = this@ThemeTest.storage
            sessionSecret = "test-secret"
            identity = IdentityProvider { if (it.id == "admin") setOf(Roles.ADMIN) else emptySet() }
            plugins += BasicTheme()
            plugins += Plain
            plugins += Skinny()
        }
        routing { get("/as/{who}") { call.sessions.set(UserSession(call.parameters["who"]!!, "x", null, "t")); call.respondText("ok") } }
    }

    private suspend fun ApplicationTestBuilder.visitor(name: String? = null): HttpClient =
        createClient { install(HttpCookies) }.also { if (name != null) it.get("/as/$name") }

    @Test
    fun `a page's regions land where its layout puts them`() = testApplication {
        site()
        val page = client.get("/about").bodyAsText()
        assertTrue(page.startsWith("<!doctype html>"), "the page writes nothing of its own: ${page.take(80)}")
        assertContains(page, "<title>About — kroom</title>")
        assertContains(page, """<main class="container layout-sidebar">""")
        assertContains(page, """<div class="content"><h1>About us</h1></div>""")
        assertContains(page, """<aside class="east">in the margin</aside>""")
        assertContains(page, """data-layout="sidebar"""")
    }

    @Test
    fun `a layout the theme lacks is its default, a region it does not show is dropped`() = testApplication {
        site()
        storage.settings("site")["theme"] = "plain"
        val page = client.get("/about").bodyAsText()
        assertContains(page, "<title>plain: About</title>")
        assertContains(page, "<h1>About us</h1><aside>in the margin</aside>")
    }

    @Test
    fun `the menu knows where the visitor is`() = testApplication {
        site()
        val page = client.get("/about").bodyAsText()
        assertContains(page, """<a href="/about" aria-current="page">About</a>""")
        assertContains(page, """<a href="/login">Login</a>""")
    }

    @Test
    fun `the head carries the house scripts, and the editor's only for a logged-in author`() = testApplication {
        site()
        val anonymous = client.get("/about").bodyAsText()
        assertContains(anonymous, "/js/kroom/domhelper.js")
        assertFalse(anonymous.contains("/js/authoring.js"))
        assertContains(visitor("admin").get("/about").bodyAsText(), "/js/authoring.js")
    }

    @Test
    fun `an admin previews a theme, activating it is for everyone`() = testApplication {
        site()
        assertContains(visitor("admin").get("/about?theme=plain").bodyAsText(), "<title>plain: About</title>")
        assertContains(client.get("/about?theme=plain").bodyAsText(), "<title>About — kroom</title>")

        val admin = visitor("admin")
        assertContains(admin.get("/api/site/themes").bodyAsText(), """"id":"basic","name":"Basic"""")
        admin.put("/api/site/theme") { contentType(ContentType.Application.Json); setBody("""{"id":"plain"}""") }
            .let { assertEquals(HttpStatusCode.OK, it.status) }
        assertContains(client.get("/about").bodyAsText(), "<title>plain: About</title>")
    }

    @Test
    fun `the site's and the theme's settings dress the page`() = testApplication {
        site()
        storage.settings("site")["name"] = "Les Vagabonds"
        storage.settings("basic")["scheme"] = "dark"
        val page = client.get("/about").bodyAsText()
        assertContains(page, "<title>About — Les Vagabonds</title>")
        assertContains(page, """data-theme="dark"""")
    }

    @Test
    fun `a skin is stylesheets over the skeleton, which renders every layout and its regions in order`() = testApplication {
        site()
        storage.settings("site")["footer"] = "© Les Vagabonds"
        val about = client.get("/about").bodyAsText()
        assertContains(about, """<link rel="stylesheet" href="/lib/pico/pico.min.css?v=""")
        assertContains(about, """<link rel="stylesheet" href="/css/basic/theme.css?v=""")
        assertContains(about, """<header class="container">""")
        assertContains(about, "© Les Vagabonds · <a href=")
        assertTrue(about.indexOf("<header") < about.indexOf("<main") && about.indexOf("<main") < about.indexOf("<footer"), "header, main, footer")
        assertContains(client.get("/welcome").bodyAsText(), """<section class="container top"><h1>Big hello</h1></section>""")
        assertContains(client.get("/story").bodyAsText(), """<main class="container layout-article">""")
        storage.settings("site")["layout"] = "article"
        assertContains(client.get("/index").bodyAsText(), """data-layout="article"""", message = "#layout() takes the site's default")
    }

    @Test
    fun `a theme rewrites a region through its partial, and adds to the head only while active`() = testApplication {
        site()
        storage.settings("site")["theme"] = "skinny"
        val page = client.get("/about").bodyAsText()
        assertContains(page, """<div class="content skinny"><h1>About us</h1></div>""")
        assertContains(page, """<meta name="skinny" content="on">""")
        assertContains(page, """<link rel="stylesheet" href="/css/skinny.css?v=""")
        storage.settings("site")["theme"] = "basic"
        val basic = client.get("/about").bodyAsText()
        assertFalse(basic.contains("skinny"), "another theme's head fragment and partial stay out")
        assertContains(basic, """<div class="content"><h1>About us</h1></div>""")
    }
}
