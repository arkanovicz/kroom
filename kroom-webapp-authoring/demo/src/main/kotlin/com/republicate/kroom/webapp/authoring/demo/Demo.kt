package com.republicate.kroom.webapp.authoring.demo

import com.republicate.kroom.plugins.forms.Forms
import com.republicate.kroom.plugins.webmaster.Webmaster
import com.republicate.kroom.webapp.authoring.AuthoredPage
import com.republicate.kroom.webapp.authoring.BasicTheme
import com.republicate.kroom.webapp.authoring.DummyTheme
import com.republicate.kroom.webapp.authoring.MemoryIdentityProvider
import com.republicate.kroom.webapp.authoring.MemoryStorage
import com.republicate.kroom.webapp.authoring.Roles
import com.republicate.kroom.webapp.authoring.Storage
import com.republicate.kroom.webapp.authoring.installContentSite
import com.republicate.kroom.webapp.velocity.velocity
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * The authoring stack as a thing you can click: `./gradlew :kroom-webapp-authoring-demo:run` (`-Pport=9000` for another port),
 * or dockerized with a mailbox beside it, `docker compose -f kroom-webapp-authoring/demo/compose.yml up`.
 *
 * Four ways in. **admin / admin** sees everything; **editor / editor** owns the pages and the menu, and every
 * block; **author / author** writes blocks and nothing else; a visitor reads. Same call an
 * application makes ([installContentSite]), a memory store that remembers its revisions, and nothing else.
 * [DemoSiteTest] asserts the same flow without the browser.
 */
fun main() {
    val port = System.getProperty("port")?.toIntOrNull() ?: 8088
    println("\n  kroom demo — http://localhost:$port/index (admin / admin, editor / editor, author / author)\n")
    embeddedServer(Netty, port = port) { demo() }.start(wait = true)
}

fun Application.demo(storage: Storage = MemoryStorage()) {
    seed(storage)
    installContentSite {
        this.storage = storage
        identity = MemoryIdentityProvider()
            .user("admin", "admin", Roles.ADMIN)
            .user("editor", "editor", Roles.EDITOR)
            .user("author", "author", Roles.AUTHOR)
        loginPage = "pages/login.html"
        sessionSecret = "demo-only-secret"
        placeholder = "*Nothing here yet for **\$name**.*"
        // the dummy theme beside the basic one: the themes panel has something to switch
        plugins += listOf(Webmaster(), Forms(), BasicTheme(), DummyTheme())
        blockTools += "stack"
    }
    velocity.registerApplication("stack") { STACK }

    // dockerized (demo/compose.yml): mail goes to Mailpit, a debugging mailbox on its own port, and each form
    // message to the admin
    System.getenv("KROOM_SMTP")?.let { smtp ->
        storage.settings("site").apply {
            set("smtpHost", smtp.substringBefore(':'))
            set("smtpPort", smtp.substringAfter(':', "25"))
            set("smtpSecurity", "none")
            set("mailFrom", "kroom demo <demo@kroom.test>")
        }
        storage.settings("forms")["notify"] = "admin@kroom.test"
    }
}

/** What the site says of itself, and the blocks its pages show before anyone edits them. */
private fun seed(storage: Storage) {
    storage.settings("site").apply {
        set("name", "kroom")
        set("description", "Pages by developers, words by authors — edited in place.")
    }
    val by = mapOf("author" to "admin")
    // a page an editor made, not a developer: its record, then its block beside it
    storage.records("site", "pages").put("about", AuthoredPage(
        path = "/about", layout = "article",
        status = AuthoredPage.PUBLISHED, author = "editor", published = System.currentTimeMillis()
    ).toJson())
    storage.content.apply {
        write("pages/about/content.md", """
            ## About

            This page has no template: an editor created it from the admin bar — a path, a title, a layout — and
            wrote this block. Publish, unpublish or delete it there; its blocks stay in the store.
        """.trimIndent(), by)
        write("pages/intro.md", """
            ## kroom

            A site whose pages are written by its developers and whose words are written by its authors — in
            place, in markdown, with a `%` where logic is needed. Log in, then hover this text: the pencil is
            the whole interface.
        """.trimIndent(), by)
        write("pages/start.md", """
            ### Try it

            - **admin / admin** — everything, and the whole bar on the left
            - **editor / editor** — the pages and the menu, every block
            - **author / author** — the blocks, nothing else

            Topics: [authoring](/topics/authoring), [themes](/topics/themes), [plugins](/topics/plugins) — and
            a topic nobody wrote yet, [go](/topics/go), to see a page before its words.
        """.trimIndent(), by)
        write("pages/stack.md", """
            ### The stack

            %foreach(${'$'}p in ${'$'}stack.projects)
            - [${'$'}p.name](${'$'}p.url) — ${'$'}p.summary
            %end

            This list is a block reading `${'$'}stack`, a tool the site hands every block: edit it and type `${'$'}`,
            then a dot — the editor offers what each level holds.
        """.trimIndent(), by)
        write("pages/contact/contact.md", """
            Say hello. The form is the forms plugin's, dropped into this block with one call:

            ${'$'}forms.contact()
        """.trimIndent(), by)
        write("pages/contact/aside.md", """
            Messages land in the admin bar, under *Messages* — and in Mailpit when the demo runs dockerized.
        """.trimIndent(), by)
        write("pages/topics/authoring/body.md", """
            One tree, read by the renderer and written by the editor: a block is a markdown file beside the
            page that shows it, and *${'$'}topic.name* is the folder this one sits in. Locks, history, drafts and a
            preview that is the page itself come with the editor.

            Built on %foreach(${'$'}p in ${'$'}topic.projects)[${'$'}p.name](${'$'}p.url)%if(${'$'}foreach.hasNext) and %end%end.
        """.trimIndent(), by)
        write("pages/topics/themes/body.md", """
            A page defines its regions and names its layout; the active theme lays them out. Two are installed
            here — switch them from the admin bar, or preview one with `?theme=dummy`.
        """.trimIndent(), by)
        write("pages/topics/plugins/body.md", """
            A plugin registers what it brings — tools for blocks, routes, an entry in the admin bar — and the
            site wires it. Two are installed: the webmaster (robots, sitemap, broken links) and the forms.
        """.trimIndent(), by)
    }
}
