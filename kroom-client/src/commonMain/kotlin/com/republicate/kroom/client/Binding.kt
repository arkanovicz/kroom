package com.republicate.kroom.client

import com.republicate.kroom.event.JsonView
import com.republicate.kroom.event.Registry

/**
 * The attribute set a page binds with, and what a build-time check can say about it against the
 * room's declarations. The same names on both sides: the DOM half (jsMain) reads them live, the
 * check reads them off the rendered markup.
 */
enum class Attr(val attr: String) {
    ROOM("data-k-room"),        // the scope: the room the element and its subtree follow
    FIELD("data-k-field"),      // a path, projected as text (or as an input's value)
    SHOW("data-k-show"),        // a Show: hidden unless it holds
    EACH("data-k-each"),        // a path to an array: the <template> child repeated per element, paths relative to it
    ON("data-k-on"),            // an event name: the element shows for `data-k-for` ms after the frame, paths against the frame
    FOR("data-k-for"),          // the span of an `on`, in ms (default 2000)
    FOLLOW("data-k-follow");    // an <a> followed when its show turns true

    companion object {
        val byName = entries.associateBy { it.attr }
    }
}

/** One attribute on one element, as the markup scanner sees it. */
data class Binding(val attr: Attr, val value: String, val inTemplate: Boolean) {
    val path: Path? get() = when (attr) { Attr.FIELD, Attr.EACH -> Path.parse(value); Attr.SHOW -> Show.parse(value).path; else -> null }
}

object Bindings {
    private val tag = Regex("""<(/?)(\w+)[^>]*>""")
    private val attr = Regex("""\b(data-k-[a-z]+)(?:\s*=\s*"([^"]*)")?""")

    /**
     * Every `data-k-*` in the markup, in document order; those inside a `<template>` (an `each`
     * body) flagged, since their paths are relative to the element. Velocity directives in the
     * markup are left alone: a `$var` inside a value is a path the check will not know.
     */
    fun scan(html: String): List<Binding> {
        val out = mutableListOf<Binding>()
        var depth = 0
        for (m in tag.findAll(html)) {
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            if (name == "template") { if (closing) depth-- else depth++; if (closing) continue }
            if (closing) continue
            for (a in attr.findAll(m.value)) {
                val attr = Attr.byName[a.groupValues[1]] ?: continue
                out += Binding(attr, a.groupValues[2], depth > 0)
            }
        }
        return out
    }

    /** The keys the room owns in every snapshot, beside the app's state. */
    val roomKeys = setOf("users", "pending", "me")

    /**
     * What the markup names that the room does not declare: a root path whose head is no state
     * field nor room key nor a key the app derives, an `on` naming no event, a value that does not
     * parse. Paths inside a template are relative and not checked (the shape of an element is not
     * declared yet).
     */
    fun check(html: String, registry: Registry, state: JsonView, derived: Set<String> = emptySet()): List<String> {
        val keys = state.fields.map { it.name }.toSet() + roomKeys + derived
        val problems = mutableListOf<String>()
        for (b in scan(html)) {
            try {
                when (b.attr) {
                    Attr.ON -> if (b.value !in registry.all) problems += "${b.attr.attr}=\"${b.value}\": no such event"
                    Attr.FIELD, Attr.SHOW, Attr.EACH -> {
                        val p = b.path!!
                        if (!b.inTemplate && !p.isMe && p.head !in keys) problems += "${b.attr.attr}=\"${b.value}\": no such field ${p.head}"
                    }
                    Attr.FOR -> if (b.value.toIntOrNull() == null) problems += "${b.attr.attr}=\"${b.value}\": not a number"
                    Attr.ROOM, Attr.FOLLOW -> {}
                }
            } catch (e: IllegalArgumentException) {
                problems += "${b.attr.attr}=\"${b.value}\": ${e.message}"
            }
        }
        return problems
    }
}
