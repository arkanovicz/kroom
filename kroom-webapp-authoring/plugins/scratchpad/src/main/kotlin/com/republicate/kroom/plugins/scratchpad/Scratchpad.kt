package com.republicate.kroom.plugins.scratchpad

import com.republicate.kroom.event.TextLogic
import com.republicate.kroom.event.TextState
import com.republicate.kroom.server.LogicRoom
import com.republicate.kroom.server.Role
import com.republicate.kroom.server.User
import com.republicate.kroom.server.Users
import com.republicate.kroom.server.room
import com.republicate.kroom.webapp.authoring.AuthoringAssets
import com.republicate.kroom.webapp.authoring.Plugin
import com.republicate.kroom.webapp.authoring.Site
import com.republicate.kroom.webapp.session.userSession
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import org.apache.velocity.engine.SafeText

/**
 * A text several people write at once, in a page: `$scratchpad.show()` is its place. One text for the site, kept
 * in memory and gone with the process — a place to try kroom's shared text (`TextState`, kroom-client's writer),
 * not a store. Whoever is logged in writes, a visitor watches.
 */
class Scratchpad(text: String = "") : Plugin {
    override val id = "scratchpad"
    override val description = "A text several people write at once, in a page: \$scratchpad.show()"

    private val room = object : LogicRoom<TextState>(ROOM, TextState(text), TextLogic()) {
        override fun role(user: User?) = if (user != null) Role.ACTOR else Role.SPECTATOR
        override fun shouldCloseWhenEmpty() = false
    }

    override fun install(site: Site) {
        site.requestTool("scratchpad") { call -> Tool(call) }
        site.foot { call -> if (call.attributes.getOrNull(SHOWN) == true) TAGS else null }
        site.routes { route("/api") { room(room) { call -> call.userSession?.let { Users.getOrCreate(it.id, it.name) } } } }
    }

    class Tool(private val call: ApplicationCall) {
        /** Where the text goes: the region's root, and the sentences its refusals are told with. */
        fun show(): SafeText {
            call.attributes.put(SHOWN, true)
            return SafeText(
                """<div id="kroom-scratchpad" class="kroom-scratchpad" data-room="$ROOM"></div>""" +
                    """<div id="messages" hidden><span data-kroom-msg="take:spectator">Log in to write.</span></div>"""
            )
        }
    }

    private companion object {
        const val ROOM = "scratchpad"
        val SHOWN = AttributeKey<Boolean>("kroom.scratchpad.shown")
        val TAGS = """<link rel="stylesheet" href="/css/scratchpad.css?v=${AuthoringAssets.VERSION}">""" + "\n" +
            """<script src="/js/scratchpad.js?v=${AuthoringAssets.VERSION}"></script>"""
    }
}
