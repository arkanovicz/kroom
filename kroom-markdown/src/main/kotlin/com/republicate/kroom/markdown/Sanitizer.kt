package com.republicate.kroom.markdown

import org.jsoup.Jsoup
import org.jsoup.nodes.Attribute
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/**
 * What a block rendered, made safe to show: html stays, scripting goes — whoever typed it, wherever it came
 * from. An allowlist over the rendered html (the order GitHub sanitizes markdown in): what is not named here
 * does not pass. No `script`, `style`, `iframe`, `object`, form control; no `on*`, `style` or `data-*` attribute
 * (the page's scripts read those); an address is `http(s):`, `mailto:`, `tel:` or relative. The `kroom-` classes
 * and ids are the house's. What needs more — an embed, a form — is a plugin's tool, answering a `SafeText`.
 */
internal object Sanitizer {

    fun clean(html: String, slots: Slots): String {
        val dirty = Jsoup.parseBodyFragment(html, BASE)
        // before the allowlist: an address a value makes is judged with the others
        slots.write(dirty.body())
        val clean = cleaner.clean(dirty)
        for (element in clean.body().select("[${Slots.ATTRIBUTE}], [class], [id], input")) tidy(element, slots)
        clean.outputSettings().prettyPrint(false)
        return clean.body().html()
    }

    private fun tidy(element: Element, slots: Slots) {
        if (element.hasAttr(Slots.ATTRIBUTE) && !slots.holds(element.attr(Slots.ATTRIBUTE))) element.removeAttr(Slots.ATTRIBUTE)
        if (element.hasAttr("class")) {
            val own = element.classNames().filterNot { it.startsWith(HOUSE) }
            if (own.isEmpty()) element.removeAttr("class") else element.attr("class", own.joinToString(" "))
        }
        if (element.id().startsWith(HOUSE)) element.removeAttr("id")
        // the one input is a task list's box
        if (element.normalName() == "input" && !(element.attr("type") == "checkbox" && element.hasAttr("disabled"))) element.remove()
    }

    private const val HOUSE = "kroom-"

    // relative addresses are kept as typed; they only have to resolve against something to be judged
    private const val BASE = "https://kroom.invalid/"

    private val WEB = arrayOf("http", "https")

    private val safelist: Safelist = object : Safelist(relaxed()) {
        override fun isSafeAttribute(tagName: String, el: Element, attr: Attribute): Boolean =
            attr.key.startsWith("aria-") || super.isSafeAttribute(tagName, el, attr)
    }
        .addTags(
            "hr", "del", "ins", "s", "kbd", "mark", "abbr", "details", "summary", "figure", "figcaption",
            "section", "article", "aside", "header", "footer", "nav", "main", "address", "time", "var", "samp",
            "dfn", "bdi", "bdo", "wbr", "ruby", "rt", "rp", "picture", "source", "video", "audio", "track", "input"
        )
        .addAttributes(":all", "class", "id", "title", "lang", "dir", "role", "hidden")
        .addAttributes("div", Slots.ATTRIBUTE)
        .addAttributes("span", Slots.ATTRIBUTE)
        .addAttributes("a", "target", "rel", "hreflang")
        .addAttributes("img", "loading", "decoding", "srcset", "sizes")
        .addAttributes("td", "align", "scope", "headers")
        .addAttributes("th", "align", "headers")
        .addAttributes("details", "open")
        .addAttributes("time", "datetime")
        .addAttributes("del", "cite", "datetime")
        .addAttributes("ins", "cite", "datetime")
        .addAttributes("video", "src", "poster", "controls", "width", "height", "preload", "loop", "muted", "playsinline")
        .addAttributes("audio", "src", "controls", "preload", "loop", "muted")
        .addAttributes("source", "src", "srcset", "sizes", "type", "media")
        .addAttributes("track", "src", "kind", "srclang", "label", "default")
        .addAttributes("input", "type", "disabled", "readonly", "checked")
        .removeProtocols("a", "href", "ftp")
        .addProtocols("a", "href", "tel")
        .addProtocols("del", "cite", *WEB)
        .addProtocols("ins", "cite", *WEB)
        .addProtocols("video", "src", *WEB)
        .addProtocols("video", "poster", *WEB)
        .addProtocols("audio", "src", *WEB)
        .addProtocols("source", "src", *WEB)
        .addProtocols("track", "src", *WEB)
        .preserveRelativeLinks(true)

    private val cleaner = Cleaner(safelist)
}
