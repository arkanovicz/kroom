package com.republicate.kroom.spike

import com.republicate.kroom.event.Chat
import com.republicate.kroom.event.ChatState
import com.republicate.kroom.event.Store
import com.republicate.kroom.event.UserPresence
import org.apache.velocity.engine.Context
import org.apache.velocity.engine.RefSite
import org.apache.velocity.engine.ReferenceInsertionEvents

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

/** The insertion handler: tells the sink in force that a value follows. Handed to the engine as an instance. */
object Insertions : ReferenceInsertionEvents {
    var sink: DomSink<*>? = null
    override fun referenceInsert(context: Context, reference: String, value: Any?, site: RefSite): Any? {
        sink?.expect(reference)
        return value ?: ""   // a null takes the null-reference path and may append nothing: the sink wants one append per value
    }
}

/** The key of an iteration, the host's knowledge. */
fun keyOf(item: Any?): String = when (item) { is UserPresence -> item.id; is Line -> item.seq.toString(); else -> item.toString() }
