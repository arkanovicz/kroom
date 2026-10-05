package com.republicate.kroom.webapp.authoring

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/** kroom-markdown's completion walk (`MarkdownMacro.SHAPES`): path steps → the level they lead to, or null. */
typealias Shape = (List<String>) -> Map<String, Any>?

/**
 * Who is editing what, in memory: one lock per block path, held by a session id and kept alive by use.
 *
 * Expiry is read, never scheduled — a lock is stale iff it has not been touched within [timeout], and the
 * question is only ever asked when someone reaches for that path. So there is no timer to cancel, no state
 * to reconcile after a restart, and a crashed editor costs exactly one timeout to the next author.
 *
 * Every transition is one [ConcurrentHashMap.compute]: two authors reaching for the same free block cannot
 * both win.
 */
class Locks(private val timeout: Duration) {

    /** [shape]: what the block sees, for the editor's completion, once the page told ([attach]); gone with the lock. */
    data class Lock(val path: String, val owner: String, val since: Long, val touched: Long, val shape: Shape? = null)

    private val locks = ConcurrentHashMap<String, Lock>()

    /** The lock now held by [owner], or null when someone else holds a fresh one. Re-entrant: an owner
     *  re-acquiring keeps [Lock.since], and taking over a stale lock starts a new one. */
    fun acquire(path: String, owner: String): Lock? {
        val now = System.currentTimeMillis()
        val lock = locks.compute(path) { _, held ->
            when {
                held == null || stale(held, now) -> Lock(path, owner, now, now)
                held.owner == owner -> held.copy(touched = now)
                else -> held
            }
        }
        return lock?.takeIf { it.owner == owner }
    }

    /** Keep [owner]'s lock alive; false when the lock is gone, stale, or someone else's. */
    fun touch(path: String, owner: String): Boolean {
        val now = System.currentTimeMillis()
        val lock = locks.compute(path) { _, held ->
            if (held != null && held.owner == owner && !stale(held, now)) held.copy(touched = now) else held
        }
        return lock != null && lock.owner == owner && !stale(lock, now)
    }

    /** Remember what [owner]'s block sees, keeping the lock alive; false when the lock is not [owner]'s. */
    fun attach(path: String, owner: String, shape: Shape): Boolean {
        val now = System.currentTimeMillis()
        val lock = locks.compute(path) { _, held ->
            if (held != null && held.owner == owner && !stale(held, now)) held.copy(touched = now, shape = shape) else held
        }
        return lock?.shape === shape
    }

    /** Give the block back; false when [owner] was not holding it. */
    fun release(path: String, owner: String): Boolean {
        var released = false
        locks.compute(path) { _, held ->
            if (held != null && held.owner == owner) { released = true; null } else held
        }
        return released
    }

    /** Who holds [path] right now — null when nobody does, dropping a stale lock on the way. */
    fun holder(path: String): Lock? {
        val now = System.currentTimeMillis()
        return locks.compute(path) { _, held -> held?.takeUnless { stale(it, now) } }
    }

    private fun stale(lock: Lock, now: Long) = now - lock.touched > timeout.inWholeMilliseconds
}
