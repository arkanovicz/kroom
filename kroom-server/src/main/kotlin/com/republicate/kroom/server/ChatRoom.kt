package com.republicate.kroom.server

import com.republicate.kroom.event.Event
import com.republicate.kroom.event.Logic
import com.republicate.kroom.event.Outcome
import com.republicate.kroom.event.Registry
import com.republicate.kroom.event.Stage
import com.republicate.kroom.event.State
import com.republicate.kroom.event.done
import com.republicate.kson.Json
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** The RAM tail, what a snapshot carries: the last [max] lines, the fold of the `chat` frames. */
class ChatState(json: Json.MutableObject = Json.MutableObject(), max: Int = 50) : State(json) {
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

    fun toMessage() = ChatMessage(seq!!, Author(author!!.getString("id")!!, author!!.getString("name")!!, author!!.getString("avatar")), text, timestamp!!)

    companion object {
        fun of(message: ChatMessage) = Chat(Json.MutableObject(message.toJson()))
    }
}

/** The rule: a line is numbered and signed. The sequence is the store's, seeded from its tail. */
class ChatLogic(last: Long) : Logic<ChatState> {
    private val seq = AtomicLong(last)
    override val registry = Registry(::Chat)
    override fun fire(state: ChatState, actor: com.republicate.kroom.event.Actor, intent: Event): Outcome<Event> {
        if (intent is Chat) {
            intent.seq = seq.incrementAndGet()
            intent.from = actor.name
            intent.author = Author(actor.id, actor.name).toJson()
            intent.timestamp = Instant.now().toString()
        }
        return done
    }
}

/**
 * A chat room: a `chat` event is numbered, framed, folded into the RAM tail, then persisted in the
 * background by [history] ([MemoryChatHistory] by default). A join gets the tail in its `state`;
 * older messages come from [before].
 */
open class ChatRoom private constructor(id: String, private val history: ChatHistory, tail: List<ChatMessage>) :
    LogicRoom<ChatState>(id, ChatState().also { s -> tail.forEach { s.history += Chat.of(it) } }, ChatLogic(tail.lastOrNull()?.seq ?: 0)) {

    // the tail and the sequence come from the store: once, before the first join
    constructor(id: String, history: ChatHistory = MemoryChatHistory()) : this(id, history, runBlocking { history.tail(50) })

    // one writer, in seq order: the store sees the messages as the room numbered them
    private val writer = Channel<Chat>(Channel.UNLIMITED)

    init {
        scope.launch { for (chat in writer) { history.append(chat.toMessage()); trace(chat.persist()) } }
    }

    /** Say something from elsewhere than a door (a bridge, a route): the same life, under the author's id. */
    suspend fun sendChat(author: Author, text: String): ChatMessage {
        val chat = act(Actor(author.id, null, author.name), Chat(Json.MutableObject("text" to text))).await()
        // the store may already have moved it on to PERSISTED: the verdict is "not rejected"
        if (chat.stage == Stage.REJECTED) throw Event.Rejected(com.republicate.kroom.event.Reason(chat.meta.reason ?: "rejected", chat.meta.args ?: Json.Object()))
        return (chat as Chat).toMessage()
    }

    override fun emitted(event: Event) {
        if (event is Chat && event.stage == Stage.APPLIED) writer.trySend(event)
    }

    /** Scroll-back: up to [n] messages older than [seq], oldest first. */
    suspend fun before(seq: Long, n: Int = 50): List<ChatMessage> = history.before(seq, n)
}
