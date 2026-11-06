package com.republicate.kroom.client

import com.republicate.kroom.event.Event
import com.republicate.kroom.event.RoomEvents
import com.republicate.kson.Json
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.*

/** Pins what a trigger posts: the form's and the attributes' text, each in its declared kind, before the check and the POST. */
class IntentsTest {

    class Say(json: Json.MutableObject = Json.MutableObject()) : Event("say", json = json) {
        val text by string().required
        val replyTo by long()
        val pinned by bool()
        val weight by double()
    }

    object Events : RoomEvents() { val say = intent(::Say) }

    private fun page(html: String): Pair<HTMLElement, MutableList<Pair<String, Json.Object>>> {
        val root = document.createElement("div") as HTMLElement
        root.innerHTML = html
        document.body!!.appendChild(root)
        val posted = ArrayList<Pair<String, Json.Object>>()
        Intents(root, Events) { type, payload -> posted += type to payload; Promise.resolve(js("({})")) }
        return root to posted
    }

    private fun click(root: HTMLElement, selector: String) = (root.querySelector(selector) as HTMLElement).click()

    @Test fun aFormsTextIsPostedInItsKindAnEmptiedFieldNullWithItsKey() {
        val (root, posted) = page("""
            <form>
              <input name="text" value="hi">
              <input type="hidden" name="replyTo" value="">
              <input type="checkbox" name="pinned" checked>
              <button data-kroom-intent="say" data-kroom-weight="1.5">Say</button>
            </form>""")
        click(root, "button")
        val (type, payload) = posted.single()
        assertEquals("say", type)
        assertEquals("hi", payload["text"])
        assertTrue(payload.containsKey("replyTo"))   // emptied on purpose: the key stays, the value is null
        assertNull(payload["replyTo"])
        assertEquals(true, payload["pinned"])   // a checkbox says `on`
        assertEquals(1.5, payload["weight"])
    }

    @Test fun aNumberIsParsedAnUndeclaredKeyStaysTextForTheShape() {
        val (root, posted) = page("""
            <form>
              <input name="text" value="hi">
              <input name="replyTo" value="42">
              <input name="evil" value="7">
              <button data-kroom-intent="say">Say</button>
            </form>""")
        click(root, "button")
        assertEquals(42L, posted.single().second["replyTo"])
        assertEquals("7", posted.single().second["evil"])
    }

    @Test fun anAttributeIsAFieldTooABadTextLeftForValidation() {
        val (root, posted) = page("""<button data-kroom-intent="say" data-kroom-text="x" data-kroom-reply-to="abc">Bad</button>""")
        click(root, "button")
        assertEquals("x", posted.single().second["text"])   // data-kroom-reply-to camel-cased
        assertEquals("abc", posted.single().second["replyTo"])   // no long reads it: `invalid`, from the view's check
    }

    @Test fun aCancelNamesWhatItWithdraws() {
        val (root, posted) = page("""<button data-kroom-intent="cancel" data-kroom-intent-of="say">Cancel</button>""")
        click(root, "button")
        assertEquals("cancel" to Json.Object("intent" to "say"), posted.single())
    }
}
