package com.republicate.kroom.client

import org.apache.velocity.engine.VelocityEngine

/**
 * A region's template in the page's language, as far as the engine holds it: [name] of [lang], else of its primary
 * tag (`fr` for `fr-BE`), else of [fallback] — a page in a language its regions were not compiled for still has them.
 */
fun VelocityEngine.localized(lang: String, fallback: String = "en", name: (String) -> String): String =
    listOf(lang, lang.substringBefore('-'), fallback).map(name).let { names -> names.firstOrNull(::resourceExists) ?: names.last() }

/** The locale a page in [lang] writes its dates in: plain English is Europe's (day first, 24 h), never the US's; a region the page names is kept. */
fun localeOf(lang: String): String = if (lang == "en") "en-150" else lang
