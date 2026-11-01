package com.republicate.kroom.server

import com.republicate.kroom.event.Chat
import com.republicate.kroom.event.ChatLogic
import com.republicate.kroom.event.ChatState
import com.republicate.kroom.event.Event
import com.republicate.kroom.event.Logic
import com.republicate.kroom.event.Stage
import com.republicate.kson.Json
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * A chat room: a `chat` event is numbered, framed, folded into the RAM tail, then persisted in the
 * background by [history] ([MemoryChatHistory] by default); its seq continues the tail's (the
 * logic numbers from the State, loaded from the store). A join gets the tail in its `state`;
 * older messages come from [before].
 */
open class ChatRoom<S : ChatState>(id: String, private val history: ChatHistory, state: S, logic: Logic<S>) : LogicRoom<S>(id, state, logic) {

    // one writer, in seq order: the store sees the messages as the room numbered them
    private val writer = Channel<Chat>(Channel.UNLIMITED)

    init {
        // the tail comes from the store: once, before the first join
        runBlocking { history.tail(state.max) }.forEach { state.history += it.toChat() }
        scope.launch { for (chat in writer) { history.append(chat.toMessage()); trace(chat.persist()) } }
    }

    /** Say something from elsewhere than a door (a bridge, a route): the same life, under the author's id. */
    suspend fun sendChat(author: Author, text: String): ChatMessage {
        val chat = act(Actor(author.id, null, author.name, author.avatar), Chat(Json.MutableObject("text" to text))).await()
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

/** A chat room and nothing more. */
fun ChatRoom(id: String, history: ChatHistory = MemoryChatHistory()): ChatRoom<ChatState> = ChatRoom(id, history, ChatState(), ChatLogic())

fun Chat.toMessage() = ChatMessage(seq!!, Author(author!!.getString("id")!!, author!!.getString("name")!!, author!!.getString("avatar")), text, timestamp!!)
fun ChatMessage.toChat() = Chat(Json.MutableObject(toJson()))
