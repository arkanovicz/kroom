package com.republicate.kroom.plugins.dummy

import com.republicate.kroom.webapp.authoring.MemoryStorage
import com.republicate.kroom.webapp.authoring.installContentSite
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/** The contract, from the theme's side: each layout it names renders, with the regions it promises. */
class DummyThemeTest {

    private val storage = MemoryStorage()

    private fun ApplicationTestBuilder.site() = application {
        installContentSite { this.storage = this@DummyThemeTest.storage; sessionSecret = "test-secret"; plugins += DummyTheme() }
    }

    @Test
    fun `every layout of the vocabulary renders, with its regions`() = testApplication {
        site()
        client.get("/index").bodyAsText().let {
            assertContains(it, """data-layout="default"""")
            assertContains(it, "<title>Home · kroom</title>")
            assertContains(it, "<p>home body</p>")
        }
        assertContains(client.get("/story").bodyAsText(), """data-layout="article"""")
        client.get("/club").bodyAsText().let {
            assertContains(it, """data-layout="sidebar"""")
            assertContains(it, "<aside><p>members</p></aside>")
        }
        client.get("/welcome").bodyAsText().let {
            assertContains(it, """data-layout="landing"""")
            assertContains(it, """<section class="dummy-hero"><h1>Big hello</h1></section>""")
            assertContains(it, "<p>features</p>")
        }
    }

    @Test
    fun `its settings dress it, and it owes kroom its two calls`() = testApplication {
        storage.settings("site")["name"] = "Les Vagabonds"
        storage.settings("dummy")["accent"] = "teal"
        site()
        val page = client.get("/index").bodyAsText()
        assertContains(page, "<title>Home · Les Vagabonds</title>")
        assertContains(page, "--dummy-accent: teal;")
        assertContains(page, "/js/kroom/domhelper.js")          // $site.head()
        assertContains(page, """<a href="/story">Story</a>""")  // $nav
        assertFalse(page.contains("kroom-admin"), "no admin bar for a visitor")
    }
}
