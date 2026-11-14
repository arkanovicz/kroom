package com.republicate.kroom.plugins.scratchpad

import com.republicate.kroom.event.Event
import com.republicate.kroom.event.Release
import com.republicate.kroom.event.Take
import com.republicate.kroom.event.TextLogic
import com.republicate.kroom.event.TextState
import com.republicate.kroom.event.Write
import com.republicate.kroom.markdown.Markdown
import com.republicate.kroom.server.LogicRoom
import com.republicate.kroom.server.Role
import com.republicate.kroom.server.User
import com.republicate.kroom.server.Users
import com.republicate.kroom.server.room
import com.republicate.kroom.webapp.assets.KroomAssets
import com.republicate.kroom.webapp.authoring.AuthoringAssets
import com.republicate.kroom.webapp.authoring.Plugin
import com.republicate.kroom.webapp.authoring.Site
import com.republicate.kroom.webapp.session.userSession
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import org.apache.velocity.engine.SafeText

/**
 * A text several people write at once, in a page: `$scratchpad.show()` is where it is written (the text, a
 * markdown toolbar, a preview beside), `$scratchpad.html()` what it says, as html, for any page to show. One text
 * for the site, kept in memory and gone with the process — a place to try kroom's shared text (`TextState`,
 * kroom-client's writer), not a store. Whoever is logged in writes, a visitor watches.
 */
class Scratchpad(text: String = "") : Plugin {
    override val id = "scratchpad"
    override val description = "A text several people write at once, in a page: \$scratchpad.show()"

    private val room = Pad(text)

    /** The room, and the text as of its last frame — kept on the loop, so a route or a page reads it from anywhere. */
    private class Pad(text: String) : LogicRoom<TextState>(ROOM, TextState(text), TextLogic()) {
        @Volatile var text: String = state.text
            private set
        override fun role(user: User?) = if (user != null) Role.ACTOR else Role.SPECTATOR
        override fun shouldCloseWhenEmpty() = false
        override fun emitted(event: Event) { if (event is Write || event is Release || event is Take) text = state.text }
    }

    // rendered when asked, once per text
    @Volatile private var rendered: Pair<String, String>? = null

    /** What the text says now, as html (kroom-markdown-core: markdown, sanitized). */
    fun html(): String {
        val text = room.text
        return rendered?.takeIf { it.first === text }?.second ?: Markdown.html(text).also { rendered = text to it }
    }

    override fun install(site: Site) {
        site.requestTool("scratchpad") { call -> Tool(call) }
        site.foot { call -> if (call.attributes.getOrNull(SHOWN) == true) TAGS else null }
        site.routes {
            route("/api") {
                room(room) { call -> call.userSession?.let { Users.getOrCreate(it.id, it.name) } }
                get("/scratchpad/html") { call.respondText(html(), ContentType.Text.Html) }
            }
        }
    }

    inner class Tool(private val call: ApplicationCall) {
        /** Where the text is written: the bar (the toolbar is put there by the page's script), the region's root, the preview's pane. */
        fun show(): SafeText {
            call.attributes.put(SHOWN, true)
            return SafeText(
                """<section class="kroom-scratchpad-frame">""" +
                    """<nav class="kroom-scratchpad-bar"><button type="button" class="kroom-scratchpad-preview-toggle outline secondary" aria-pressed="false">Preview</button></nav>""" +
                    """<div class="kroom-scratchpad-panes">""" +
                    """<div id="kroom-scratchpad" class="kroom-scratchpad kroom-text" data-room="$ROOM"></div>""" +
                    """<article class="kroom-scratchpad-preview" hidden></article>""" +
                    """</div></section>""" +
                    """<div id="messages" hidden><span data-kroom-msg="take:spectator">Log in to write.</span></div>"""
            )
        }

        /** What the text says now, as html: for a page that shows it, not the place it is written in. */
        fun html(): SafeText = SafeText(this@Scratchpad.html())
    }

    private companion object {
        const val ROOM = "scratchpad"
        val SHOWN = AttributeKey<Boolean>("kroom.scratchpad.shown")
        val TAGS = listOf(
            KroomAssets.markdownStyle(),
            KroomAssets.textStyle(),
            """<link rel="stylesheet" href="/css/scratchpad.css?v=${AuthoringAssets.VERSION}">""",
            KroomAssets.markdownScript(),
            """<script src="/js/scratchpad.js?v=${AuthoringAssets.VERSION}"></script>"""
        ).joinToString("\n")
    }
}
