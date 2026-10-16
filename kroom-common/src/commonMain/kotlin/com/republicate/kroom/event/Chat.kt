package com.republicate.kroom.event

import com.republicate.kson.Json

/** The RAM tail, what a snapshot carries: the last [max] lines, the fold of the `chat` frames. A room with more extends it. */
open class ChatState(json: Json.MutableObject = Json.MutableObject(), val max: Int = 50) : State(json) {
    val history by log(::Chat, max)
    override fun reduce(frame: Event) { if (frame is Chat) history += frame }
}

/** A line of chat: the text comes in, the rule adds who, when and the message's seq. */
class Chat(json: Json.MutableObject = Json.MutableObject()) : Event("chat", json = json) {
    val text by string().required
    var seq by long().out
    var from by string().out
    var author by obj().out
    var timestamp by string().out
}
