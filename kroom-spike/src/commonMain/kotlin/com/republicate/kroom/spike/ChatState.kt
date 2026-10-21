package com.republicate.kroom.spike

import com.republicate.kroom.event.Chat
import com.republicate.kroom.event.Event
import com.republicate.kroom.event.Join
import com.republicate.kroom.event.Leave
import com.republicate.kroom.event.Status
import com.republicate.kson.Json

/*
 * Spec §6.2:
 *
 *   room chat {
 *     state(history: log<Chat, 50>)        // users, me: presence
 *     event chat(text: String) -> chat(author: User, timestamp: String)
 *   }
 *
 * `users` is presence, kroom's base State in the end; here it sits on the chat's so the spike
 * stays one class. `history` is keyed by seq for the spike (a log is the next kind to write).
 */

class User(values: Values) : View(values) {
    var name by string()
    var status by string()
}

class Line(values: Values) : View(values) {
    var author by string()
    var text by string()
}

class ChatState(storage: Storage) : State(storage) {
    val users by keyed(::User)
    val history by keyed(::Line)

    override fun reduce(frame: Event) {
        when (frame) {
            is Join -> frame.user?.let { users.add(it.id, mapOf("name" to it.name, "status" to it.status?.name)) }
            is Leave -> frame.user?.let { users.remove(it.id) }
            is Status -> frame.user?.let { users[it]?.status = frame.status.name }
            is Chat -> frame.seq?.let { history.add(it.toString(), mapOf("author" to frame.from, "text" to frame.text)) }
            else -> {}
        }
    }

    /** The `state` frame: every branch regenerated. */
    fun snapshot(json: Json.Object) {
        load(users, json.getObject("users"))
        load(history, json.getObject("history"))
    }

    private fun load(field: Keyed<*>, entries: Json.Object?) {
        field.clear()
        if (entries != null) for ((key, v) in entries) field.add(key, (v as Json.Object).entries.associate { (k, x) -> k to x?.toString() })
    }
}
