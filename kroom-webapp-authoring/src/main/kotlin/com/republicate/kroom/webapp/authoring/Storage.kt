package com.republicate.kroom.webapp.authoring

import com.republicate.kson.Json
import java.util.concurrent.atomic.AtomicLong

/**
 * Everything a site keeps, by kind — the one persistence contract kroom and its plugins write against:
 *
 * - [content]: the blocks, read by the renderer and written by the editor (a velocity `ResourceLoader`);
 * - [settings]: a handful of named strings per namespace — a plugin's configuration;
 * - [records]: rows a plugin owns, per collection — form submissions, redirect rules, subscribers;
 * - [media]: uploaded files, under names the store hands out.
 *
 * A namespace is a plugin id (`[a-z0-9_-]+`), so no plugin reads another's by accident. The shipped
 * [MemoryStorage] and [FileStorage] are demo-grade; an application maps the same four kinds onto what it
 * already runs (a git content tree, a database through skorm — a collection is a table's worth of rows —, an
 * object store).
 */
interface Storage {
    val content: ResourceStore
    fun settings(namespace: String): Settings
    fun records(namespace: String, collection: String): Records
    val media: Media
}

/** A namespace's configuration: small strings, read often, written by an admin. */
interface Settings {
    operator fun get(key: String): String?

    /** Null removes the key. */
    operator fun set(key: String, value: String?)

    fun all(): Map<String, String>
}

/**
 * A collection of JSON rows under ids the store hands out. Ids sort by creation time, so [list] reads
 * newest first without an index of its own.
 */
interface Records {
    /** Store [record] under a new id, answered. */
    fun add(record: Json.Object): String

    /** Store [record] under [id], replacing what was there. */
    fun put(id: String, record: Json.Object)

    fun get(id: String): Json.Object?

    /** False when there was nothing under [id]. */
    fun delete(id: String): Boolean

    /** Rows by id, newest first. */
    fun list(limit: Int = 50): Map<String, Json.Object>
}

private val NAMESPACE = Regex("[a-z0-9_-]+")

/** A namespace or collection as a path segment — never a traversal. */
internal fun segment(name: String): String {
    require(NAMESPACE.matches(name)) { "invalid storage name: '$name'" }
    return name
}

private val sequence = AtomicLong()

/** Creation-ordered: millis then a counter, both fixed-width base 36, so string order is time order. */
internal fun newRecordId(): String =
    System.currentTimeMillis().toString(36).padStart(9, '0') + (sequence.getAndIncrement() % 1_679_616).toString(36).padStart(4, '0')
