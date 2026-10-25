package com.republicate.kroom.spike

/**
 * The few operations a sink needs on a live tree, over the platform's node type: jsdom's `Node` in the
 * browser, a double on the JVM. The tree is the sink's only memory of what it built: a site's current
 * value is read back from its node, never kept beside it.
 */
interface Tree<N : Any> {
    fun element(tag: String): N
    fun text(data: String): N
    fun textOf(text: N): String
    fun setText(text: N, data: String)
    fun getAttribute(element: N, name: String): String?
    fun setAttribute(element: N, name: String, value: String)
    fun removeAttribute(element: N, name: String)
    /** [before] null appends. */
    fun insertBefore(parent: N, child: N, before: N?)
    fun remove(node: N)
    fun parent(node: N): N?
    fun nextSibling(node: N): N?
}
