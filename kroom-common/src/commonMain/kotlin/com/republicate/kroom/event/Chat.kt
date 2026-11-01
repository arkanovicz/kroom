package com.republicate.kroom.event

import com.republicate.kson.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Who said it: a user's id, or an external one (`discord:<snowflake>`); the avatar is an image URL. */
class Author(json: Json.MutableObject = Json.MutableObject()) : JsonView(json) {
    constructor(id: String, name: String, avatar: String? = null) :
        this(Json.MutableObject("id" to id, "name" to name).also { a -> avatar?.let { a["avatar"] = it } })

    val id by string().required
    val name by string().required
    val avatar by string()

    companion object {
        fun of(actor: Actor) = Author(actor.id, actor.name, actor.avatar)
    }
}

/**
 * The RAM tail, what a snapshot carries: the last [max] lines, the fold of the `chat` frames, each a [line]
 * (`::Chat`, or the app's subclass). A room with more extends it.
 */
open class ChatState<C : Chat>(json: Json.MutableObject = Json.MutableObject(), val max: Int = 50, val line: (Json.MutableObject) -> C) : State(json) {
    val history by log(line, max)
    override fun reduce(event: Event) {
        super.reduce(event)
        if (event is Chat) history += line(event.json)
    }
}

/** A chat's tail of plain lines. */
fun ChatState(json: Json.MutableObject = Json.MutableObject(), max: Int = 50): ChatState<Chat> = ChatState(json, max, ::Chat)

/**
 * A line of chat: the text comes in, the logic adds who, when and the line's seq. One shape for the frame, the
 * tail, the store and the scroll-back: an app extends it with its own `out` fields (reactions), and they travel.
 */
open class Chat(json: Json.MutableObject = Json.MutableObject()) : Event("chat", json = json) {
    val text by string().required
    init { check { _: ChatState<*>, _ -> if (text.isBlank()) Reason("blank") else null } }
    var seq by long().out
    var author by obj(::Author).out
    var timestamp by string().out
}

/** The chat's alphabet: `chat` over [line], beside kroom's. A room composing a chat extends it. */
open class ChatEvents<C : Chat>(line: (Json.MutableObject) -> C) : RoomEvents() {
    val chat = intent(line)
}

/** A chat of plain lines. */
fun ChatEvents(): ChatEvents<Chat> = ChatEvents(::Chat)

/** A line is numbered after the State's last and signed. A composing logic delegates its `chat` handler to [chat]. */
@OptIn(ExperimentalTime::class)
class ChatLogic<C : Chat>(private val clock: Clock, events: ChatEvents<C>) : Logic<ChatState<C>>(events) {
    init { on(events.chat) { state, actor, line -> chat(state, actor, line) } }

    fun chat(state: ChatState<*>, actor: Actor, chat: Chat): Outcome<Nothing> {
        chat.seq = (state.history.lastOrNull()?.seq ?: 0) + 1
        chat.author = Author.of(actor)
        chat.timestamp = clock.now().toString()
        return done
    }
}

/** The logic of a chat of plain lines. */
@OptIn(ExperimentalTime::class)
fun ChatLogic(clock: Clock = Clock.System): ChatLogic<Chat> = ChatLogic(clock, ChatEvents())
