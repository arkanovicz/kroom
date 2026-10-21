package com.republicate.kroom.spike

import org.w3c.dom.Element
import org.w3c.dom.Text
import org.w3c.dom.asList
import org.w3c.dom.get

/*
 * Spec §5: the browser's storage of a keyed field is its container in the page; an item's fields
 * are sites (here text nodes) inside the item; the field's size is a site too when the page reads
 * it. The compiler emits one [KeyedSite] per `#foreach` over a keyed field; this is its runtime.
 */

/** One keyed field as a page reads it: the container, the item renderer, the site of each item field, the site of the size. */
class KeyedSite(
    val name: String,
    val container: Element,
    val render: (key: String, values: Map<String, String?>) -> Element,
    val sites: Map<String, Int>,     // item field → index of its text node in the item
    val size: Text? = null
)

/** An item's values are its sites: reading is the node's data, writing sets it. */
class DomValues(private val item: Element, private val sites: Map<String, Int>) : Values {
    override fun get(name: String) = sites[name]?.let { (item.childNodes[it] as? Text)?.data }
    override fun set(name: String, value: String?) { sites[name]?.let { (item.childNodes[it] as? Text)?.data = value ?: "" } }
}

/** A keyed list as a container's children; the browser's `id` index finds an item (`<field>.<key>`). */
class DomKeyed<V : View>(private val site: KeyedSite, private val view: (Values) -> V) : Keyed<V> {
    private fun item(key: String): Element? = site.container.ownerDocument?.getElementById("${site.name}.$key")

    override val size get() = site.container.children.length
    override fun get(key: String) = item(key)?.let { view(DomValues(it, site.sites)) }
    override fun add(key: String, values: Map<String, String?>) {
        val present = item(key)
        if (present != null) { val target = DomValues(present, site.sites); values.forEach { (k, v) -> target[k] = v } }
        else { site.container.appendChild(site.render(key, values)); count() }
    }
    override fun remove(key: String) { item(key)?.let { it.remove(); count() } }
    override fun clear() { site.container.children.asList().forEach { it.remove() }; count() }
    override fun iterator() = site.container.children.asList().map { view(DomValues(it, site.sites)) }.iterator()

    private fun count() { site.size?.data = size.toString() }
}

/** The page's storage: one [KeyedSite] per keyed field the page reads, [Absent] for the rest. */
class DomStorage(private val sites: Map<String, KeyedSite>) : Storage {
    override fun <V : View> keyed(name: String, view: (Values) -> V): Keyed<V> = sites[name]?.let { DomKeyed(it, view) } ?: Absent()
}
