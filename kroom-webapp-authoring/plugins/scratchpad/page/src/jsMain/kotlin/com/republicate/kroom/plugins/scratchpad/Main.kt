package com.republicate.kroom.plugins.scratchpad

import com.republicate.kroom.client.Room
import com.republicate.kroom.client.connect
import com.republicate.kroom.client.write
import com.republicate.kroom.event.TextEvents
import com.republicate.kroom.event.TextState
import kotlinx.browser.document
import org.apache.velocity.engine.Config
import org.apache.velocity.engine.VelocityEngine
import org.apache.velocity.engine.resource.StockResourceLoader
import velocity.templates.VelocityTemplates

/** The scratchpad's page: the text's fold is [TextState], the class the server runs; its region is written in through kroom-client's writer. */
fun main() {
    val root = document.getElementById("kroom-scratchpad") ?: return
    val room = Room(root.getAttribute("data-room") ?: "scratchpad", TextState(), TextEvents(), engine)
    room.write("scratchpad/text.html", root)
    room.connect()
}

/** The region, compiled at build: an engine without an interpreter, so no parser ships. */
private val engine = VelocityEngine(null, Config(informalNavigation = true)).apply { addResourceLoader("stock", StockResourceLoader(VelocityTemplates::resolve)) }
