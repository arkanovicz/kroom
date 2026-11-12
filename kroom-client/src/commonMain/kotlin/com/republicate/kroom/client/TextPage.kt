package com.republicate.kroom.client

import com.republicate.kroom.event.Part
import com.republicate.kroom.event.State
import com.republicate.kroom.event.TextState

/**
 * What the page of a text knows and no room does: which client it is. A region reads it to tell the part this tab
 * holds — the one it shows as a textarea — from the parts held by others, the same user's other tab included.
 */
class TextPage(private val room: Room<out TextState>, private val client: String?) : State() {

    /** Whether [part] is this tab's to write. */
    fun mine(part: Part): Boolean = part.holder != null && part.holder == room.me?.id && part.client == client

    /** Who writes [part], by name; null when it is free. */
    fun holder(part: Part): String? = part.holder?.let { room.state.users[it]?.name ?: it }
}
