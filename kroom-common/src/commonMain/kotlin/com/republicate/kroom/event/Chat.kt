package com.republicate.kroom.event

import com.republicate.kson.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** The RAM tail, what a snapshot carries: the last [max] lines, the fold of the `chat` frames. A room with more extends it. */
open class ChatState(json: Json.MutableObject = Json.MutableObject(), val max: Int = 50) : State(json) {
    val history by log(::Chat, max)
    override fun reduce(event: Event) {
        super.reduce(event)
        if (event is Chat) history += event
    }
}

/** A line of chat: the text comes in, the logic adds who, when and the line's seq. */
class Chat(json: Json.MutableObject = Json.MutableObject()) : Event("chat", json = json) {
    val text by string().required
    var seq by long().out
    var from by string().out
    var author by obj().out
    var timestamp by string().out
}

/** A line is numbered after the State's last and signed. A composing logic delegates its `chat` here. */
@OptIn(ExperimentalTime::class)
class ChatLogic(private val clock: Clock = Clock.System) : Logic<ChatState> {
    override val registry = Registry(::Chat)

    override fun decide(state: ChatState, actor: Actor, intent: Event): Outcome<Event> = if (intent is Chat) chat(state, actor, intent) else done

    fun chat(state: ChatState, actor: Actor, chat: Chat): Outcome<Nothing> {
        chat.seq = (state.history.lastOrNull()?.seq ?: 0) + 1
        chat.from = actor.name
        chat.author = Json.Object("id" to actor.id, "name" to actor.name)
        chat.timestamp = clock.now().toString()
        return done
    }
}
