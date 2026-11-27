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

    class Vote(json: Json.MutableObject = Json.MutableObject()) : Event("vote", json = json) {
        val seq by long().required
        val value by double().required
    }

    object Events : RoomEvents() { val say = intent(::Say); val vote = intent(::Vote) }

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

    /** Two triggers of one intent with different fields run independently; the same one twice is one POST; a reason lands after its own trigger. */
    @Test fun triggersOfOneIntentAreBusyAndSettledEachOnItsOwn(): Promise<Unit> {
        val root = document.createElement("div") as HTMLElement
        root.innerHTML = """
            <button id="a" data-kroom-intent="vote" data-kroom-seq="3" data-kroom-value="1.0">👍</button>
            <button id="b" data-kroom-intent="vote" data-kroom-seq="7" data-kroom-value="-1.0">👎</button>"""
        document.body!!.appendChild(root)
        val replies = ArrayList<Pair<(dynamic) -> Unit, (Throwable) -> Unit>>()
        val posted = ArrayList<Json.Object>()
        Intents(root, Events) { _, payload -> posted += payload; Promise { resolve, reject -> replies += Pair(resolve, reject) } }
        val a = root.querySelector("#a") as HTMLElement
        val b = root.querySelector("#b") as HTMLElement
        a.click(); a.click(); b.click()
        assertEquals(listOf(3L, 7L), posted.map { it["seq"] })   // the second click on a swallowed, b's not
        assertTrue(a.hasAttribute("disabled") && b.hasAttribute("disabled"))
        replies[0].second(js("({data: {reason: 'already_voted'}})").unsafeCast<Throwable>())
        return Promise.resolve(Unit).then {   // a's verdict, delivered: a free with its reason, b still busy
            assertFalse(a.hasAttribute("disabled"))
            assertTrue(b.hasAttribute("disabled"))
            assertEquals("already_voted", (a.nextElementSibling as HTMLElement).textContent)
            assertEquals("b", root.querySelector(".intent-reason + *")?.id)
            replies[1].first(js("({})"))
        }.then {
            assertFalse(b.hasAttribute("disabled"))
            assertEquals(1, root.querySelectorAll(".intent-reason").length)
            a.click()   // free again: posts
            assertEquals(3, posted.size)
        }
    }

    /** A refusal's sentence is found by `intent:reason`, its args filling the `{k}`; raw, args and all, only without one. */
    @Test fun aReasonWithArgsFindsItsSentenceAndFillsIt(): Promise<Unit> {
        val messages = document.createElement("div").also { it.id = "messages"; document.body!!.appendChild(it) }
        messages.innerHTML = """<span data-kroom-msg="vote:closed">Closed by {by}.</span>"""
        val root = document.createElement("div") as HTMLElement
        root.innerHTML = """<button id="a" data-kroom-intent="vote" data-kroom-seq="3">👍</button><button id="b" data-kroom-intent="say">Say</button>"""
        document.body!!.appendChild(root)
        Intents(root, Events) { _, _ -> Promise.reject(js("({data: {reason: 'closed', args: {by: 'Alice'}}})").unsafeCast<Throwable>()) }
        (root.querySelector("#a") as HTMLElement).click()
        (root.querySelector("#b") as HTMLElement).click()
        return Promise.resolve(Unit).then { }.then {
            assertEquals("Closed by Alice.", root.querySelector("#a + .intent-reason")?.textContent)
            assertEquals("closed (by: Alice)", root.querySelector("#b + .intent-reason")?.textContent)   // no say:closed on the page
            messages.remove(); root.remove()
        }
    }

    @Test fun aCancelNamesWhatItWithdraws() {
        val (root, posted) = page("""<button data-kroom-intent="cancel" data-kroom-intent-of="say">Cancel</button>""")
        click(root, "button")
        assertEquals("cancel" to Json.Object("intent" to "say"), posted.single())
    }
}
