package com.republicate.kroom.spike

import com.republicate.kroom.event.Chat
import com.republicate.kroom.event.ChatState
import com.republicate.kroom.event.Store
import com.republicate.kroom.event.UserPresence

/** What the page reads: presence and the chat's tail, over the client's fold. */
class ChatView(private val store: Store<ChatState>) {
    val users: List<UserPresence> get() = store.users
    val history: List<Line> get() = store.state.history.map(::Line)
}

/** A chat line as the page reads it: `author` is a plain JSON object on the event, the page wants a property. */
class Line(val chat: Chat) {
    val seq get() = chat.seq
    val author get() = chat.author?.getString("name")
    val text get() = chat.text
}

/** The key of an iteration, the host's knowledge: what must survive a change of the item's content. */
fun keyOf(value: Any?, index: Int): Any? = when (value) { is UserPresence -> value.id; is Line -> value.seq; else -> index }
