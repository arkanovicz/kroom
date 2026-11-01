package com.republicate.kroom.server

import com.republicate.kroom.event.Author
import com.republicate.kroom.event.Chat
import com.republicate.kroom.event.ChatLogic
import com.republicate.kroom.event.ChatState
import com.republicate.kroom.event.Event
import com.republicate.kroom.event.Logic
import com.republicate.kroom.event.Reason
import com.republicate.kroom.event.Stage
import com.republicate.kson.Json
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * A chat room: a `chat` event is numbered, framed, folded into the RAM tail, then persisted in the
 * background by [history] ([MemoryChatHistory] by default); its seq continues the tail's (the
 * logic numbers from the State, loaded from the store). A join gets the tail in its `state`;
 * older lines come from [before]. Lines are the State's [ChatState.line] everywhere.
 */
open class ChatRoom<C : Chat, S : ChatState<C>>(id: String, private val history: ChatHistory<C>, state: S, logic: Logic<S>) : LogicRoom<S>(id, state, logic) {

    // one writer, in seq order: the store sees the lines as the room numbered them
    private val writer = Channel<Pair<Chat, C>>(Channel.UNLIMITED)

    // the State's lines and the store's never share a JSON: one may change while the other is read
    private fun copy(line: Chat): C = state.line(line.json.copy())

    init {
        // the tail comes from the store: once, before the first join
        runBlocking { history.tail(state.max) }.forEach { state.history += copy(it) }
        scope.launch { for ((chat, line) in writer) { history.append(line); trace(chat.persist()) } }
    }

    /** Say something from elsewhere than a door (a bridge, a route): the same life, under the author's id. */
    suspend fun sendChat(author: Author, text: String): C {
        val chat = act(Actor(author.id, null, author.name, author.avatar), state.line(Json.MutableObject("text" to text))).await()
        // the store may already have moved it on to PERSISTED: the verdict is "not rejected"
        if (chat.stage == Stage.REJECTED) throw Event.Rejected(Reason(chat.meta.reason ?: "rejected", chat.meta.args ?: Json.Object()))
        return copy(chat as Chat)
    }

    override fun emitted(event: Event) {
        // copied on the loop, before anything may touch the State's line
        if (event is Chat && event.stage == Stage.APPLIED) writer.trySend(event to copy(event))
    }

    /** Scroll-back: up to [n] lines older than [seq], oldest first. */
    suspend fun before(seq: Long, n: Int = 50): List<C> = history.before(seq, n)
}

/** A chat room and nothing more. */
fun ChatRoom(id: String, history: ChatHistory<Chat> = MemoryChatHistory()): ChatRoom<Chat, ChatState<Chat>> = ChatRoom(id, history, ChatState(), ChatLogic())
