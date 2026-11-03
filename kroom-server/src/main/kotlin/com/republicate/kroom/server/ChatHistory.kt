package com.republicate.kroom.server

import com.republicate.kroom.event.Chat
import com.republicate.kroom.event.Event
import java.util.ArrayDeque

/**
 * Where a room's chat lives beyond its RAM tail — one instance per room, over the room's line class (`Chat`, or
 * the app's subclass: what it adds is stored and served like the rest). The loop never waits on it: a line is
 * numbered and broadcast first, then [append]ed in the background; [tail] runs once, when the room starts, to
 * seed the sequence and the snapshot; [before] and [after] serve the history route (scroll-back, and reading on
 * from a line jumped to). The room hands over and takes
 * back copies: a line here is the store's own.
 */
interface ChatHistory<C : Chat> {
    /** The last [n] lines, oldest first. */
    suspend fun tail(n: Int): List<C>

    /** Persist a line; called off the loop, in order of seq. */
    suspend fun append(line: C)

    /**
     * Every other frame (a reaction, an edit, a request the logic left to the store), in the same order as the lines:
     * one never reaches the store before a line it names. Nothing by default.
     */
    suspend fun persist(event: Event) {}

    /** Up to [n] lines older than [seq], oldest first. */
    suspend fun before(seq: Long, n: Int): List<C>

    /** Up to [n] lines newer than [seq], oldest first. */
    suspend fun after(seq: Long, n: Int): List<C>
}

/**
 * The default: the last [capacity] lines in memory, gone with the process.
 */
class MemoryChatHistory<C : Chat>(private val capacity: Int = 1000) : ChatHistory<C> {
    private val lines = ArrayDeque<C>()

    override suspend fun tail(n: Int): List<C> = synchronized(lines) { lines.toList().takeLast(n) }

    override suspend fun append(line: C) = synchronized(lines) {
        lines.addLast(line)
        while (lines.size > capacity) lines.removeFirst()
    }

    override suspend fun before(seq: Long, n: Int): List<C> = synchronized(lines) {
        lines.filter { it.seq!! < seq }.takeLast(n)
    }

    override suspend fun after(seq: Long, n: Int): List<C> = synchronized(lines) {
        lines.filter { it.seq!! > seq }.take(n)
    }
}
