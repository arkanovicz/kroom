package com.republicate.kroom.examples.chifoumi.sse

import com.republicate.kroom.examples.chifoumi.game.ChifoumiLogic
import com.republicate.kroom.examples.chifoumi.game.ChifoumiState
import com.republicate.kroom.event.Chat
import com.republicate.kroom.server.ChatRoom
import com.republicate.kroom.server.MemoryChatHistory

/** The one room of the arena: a chat room whose logic is the game's. Every page listens to it. */
object ChifoumiLobby : ChatRoom<Chat, ChifoumiState>("chifoumi-lobby", MemoryChatHistory(), ChifoumiState(), ChifoumiLogic()) {
    override fun shouldCloseWhenEmpty(): Boolean = false
}
