package com.republicate.kroom.spike

import org.w3c.dom.Element
import org.w3c.dom.Text

/**
 * What the compiler would emit for the page of §6.2, written by hand (the compiler's golden):
 *
 *   ##@ state: ChatState
 *   <span>$state.users.size()</span> online
 *   <ul>
 *   #foreach($user in $state.users)
 *     <li>$user.name ($user.status)</li>
 *   #end
 *   </ul>
 *
 * The page does not read `history`: that branch is absent from its storage.
 */
class ChatPage(root: Element) {
    private val doc = root.ownerDocument!!
    private val sizeText: Text = doc.createTextNode("0")
    private val users: Element = doc.createElement("ul")

    init {
        doc.createElement("span").let { it.appendChild(sizeText); root.appendChild(it) }
        root.appendChild(doc.createTextNode(" online\n"))
        users.id = "users"
        root.appendChild(users)
    }

    val storage: Storage = DomStorage(mapOf(
        "users" to KeyedSite("users", users, ::user, sites = mapOf("name" to 0, "status" to 2), size = sizeText)
    ))

    /** `<li>$user.name ($user.status)</li>`: text 0 = name, text 1 = " (", text 2 = status, text 3 = ")". */
    private fun user(key: String, v: Map<String, String?>): Element = doc.createElement("li").apply {
        id = "users.$key"
        appendChild(doc.createTextNode(v["name"] ?: ""))
        appendChild(doc.createTextNode(" ("))
        appendChild(doc.createTextNode(v["status"] ?: ""))
        appendChild(doc.createTextNode(")"))
    }
}
