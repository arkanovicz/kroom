package com.republicate.kroom.client

import com.republicate.kroom.event.Presence
import com.republicate.kroom.event.Status
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.AddEventListenerOptions
import org.w3c.dom.events.Event

/**
 * The tab reports its presence, kroom's `status`: hidden → AWAY, no input for [idleAfter] ms → IDLE, back → ONLINE.
 * Posted on change only, through [act] (a spectator posts nothing; a refusal is one console line), and again after
 * each snapshot when not ONLINE: a reconnect is a join, and the room starts a joiner ONLINE. Returns what stops it.
 */
fun Room<*>.report(idleAfter: Int = 120_000): () -> Unit {
    fun hidden() = document.asDynamic().hidden == true   // Page Visibility, not in the stdlib's Document
    var status = Presence.ONLINE
    var timer: Int? = null
    var stopped = false
    fun post() { if (me != null) act(Status().also { it.status = status }).catch { err -> console.warn("status $status refused: ${err.message}") } }
    fun set(s: Presence) { if (s != status) { status = s; post() } }
    fun active() {
        if (hidden()) return
        timer?.let(window::clearTimeout)
        timer = window.setTimeout({ set(Presence.IDLE) }, idleAfter)
        set(Presence.ONLINE)
    }
    val visibility: (Event) -> Unit = { if (hidden()) { timer?.let(window::clearTimeout); set(Presence.AWAY) } else active() }
    val activity: (Event) -> Unit = { active() }
    val inputs = listOf("mousedown", "mousemove", "keydown", "touchstart", "scroll")
    document.addEventListener("visibilitychange", visibility)
    inputs.forEach { document.addEventListener(it, activity, AddEventListenerOptions(passive = true)) }
    react { name, _ -> if (!stopped && name == "state" && status != Presence.ONLINE) post() }
    if (hidden()) set(Presence.AWAY) else active()
    return {
        stopped = true
        timer?.let(window::clearTimeout)
        document.removeEventListener("visibilitychange", visibility)
        inputs.forEach { document.removeEventListener(it, activity) }
    }
}
