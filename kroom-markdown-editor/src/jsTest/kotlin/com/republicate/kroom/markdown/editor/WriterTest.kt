package com.republicate.kroom.markdown.editor

import com.republicate.kson.Json
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.*

/** Pins what a tab holding a part posts as it is typed in: one intent at a time, the text as it is when it leaves. */
class WriterTest {

    /** A region under `document.body`, a [Writer] on it, an `act` that records and answers when told. */
    private class Desk(idle: Int = 0) {
        val root = (document.createElement("div") as HTMLElement).also { document.body!!.appendChild(it) }
        val posted = ArrayList<String>()
        private val replies = ArrayList<Pair<(dynamic) -> Unit, (Throwable) -> Unit>>()
        val writer = Writer(root, { type, payload ->
            posted += "$type $payload"
            Promise { resolve, reject -> replies += Pair(resolve, reject) }
        }, idle)

        /** The frame: the part's textarea, as the region's merge leaves it. */
        fun show(id: String, value: String = ""): HTMLTextAreaElement {
            root.innerHTML = """<textarea data-kroom-part="$id"></textarea>"""
            return area().also { it.value = value }
        }

        fun area() = root.querySelector("textarea") as HTMLTextAreaElement

        fun reply(value: dynamic = js("({})")) { assertTrue(replies.isNotEmpty(), "no request pending"); replies.removeAt(0).first(value) }
        fun refuse(reason: dynamic = js("({data: {reason: 'gone'}})")) { assertTrue(replies.isNotEmpty(), "no request pending"); replies.removeAt(0).second(reason.unsafeCast<Throwable>()) }
        val pending get() = replies.size

        /** What a browser does: the value, the caret at its end, then the `input`. */
        fun type(area: HTMLTextAreaElement, value: String, inputType: String = "insertText") {
            area.value = value
            area.setSelectionRange(value.length, value.length)
            val init: dynamic = js("({bubbles: true})")
            init.inputType = inputType
            area.dispatchEvent(js("new InputEvent('input', init)").unsafeCast<Event>())
        }

        fun keyup(area: HTMLTextAreaElement) { area.dispatchEvent(Event("keyup", js("({bubbles: true})"))) }
        fun focusout(area: HTMLTextAreaElement) { area.dispatchEvent(js("new FocusEvent('focusout', {bubbles: true})").unsafeCast<Event>()) }

        fun button() = (document.createElement("button") as HTMLElement).also { document.body!!.appendChild(it) }
    }

    private fun write(id: String, text: String, caret: Int = text.length) = "write ${Json.Object("id" to id, "text" to text, "caret" to caret)}"
    private fun take(payload: Json.Object) = "take $payload"
    private fun release(id: String) = "release ${Json.Object("id" to id)}"

    private fun tick(ms: Int = 0): Promise<Unit> = Promise { resolve, _ -> window.setTimeout({ resolve(Unit) }, ms) }

    /** Each step after the replies of the one before settled (a macrotask between steps). */
    private fun play(vararg steps: () -> Unit): Promise<Unit> =
        steps.fold(Promise.resolve(Unit)) { p, step -> p.then { step() }.then { tick() }.unsafeCast<Promise<Unit>>() }

    @Test fun aTextareaFoundAfterAMergeHasTheFocusTheCaretAtTheEndAndOneWriteSaysWhereItsHolderIs() {
        val desk = Desk()
        val area = desk.show("1", "Hello")
        desk.writer.merged()
        assertSame(area, document.activeElement)
        assertEquals(5, area.selectionStart)
        assertEquals(5, area.selectionEnd)
        assertEquals(listOf(write("1", "Hello", 5)), desk.posted)
    }

    @Test fun typingPostsOneWriteAtATimeTheLatestTextWhenTheReplyComesNothingWhenNothingChanged(): Promise<Unit> {
        val desk = Desk()
        val area = desk.show("1", "")
        desk.writer.merged()
        return play(
            { desk.reply() },
            {
                desk.type(area, "Hello")
                assertEquals(write("1", "Hello"), desk.posted.last())
                desk.type(area, "Hello w")
                desk.type(area, "Hello world")
                assertEquals(2, desk.posted.size)   // the first write's reply pending: nothing more
            },
            { assertEquals(2, desk.posted.size); desk.reply() },
            {
                assertEquals(listOf(write("1", ""), write("1", "Hello"), write("1", "Hello world")), desk.posted)
                desk.reply()
            },
            { desk.keyup(area) },
            { assertEquals(3, desk.posted.size); assertEquals(0, desk.pending) })
    }

    @Test fun aCaretMovedAlonePostsTheSameTextWithTheNewCaret(): Promise<Unit> {
        val desk = Desk()
        val area = desk.show("1", "Hello")
        desk.writer.merged()
        return play(
            { desk.reply() },
            { area.setSelectionRange(2, 2); desk.keyup(area) },
            { assertEquals(listOf(write("1", "Hello", 5), write("1", "Hello", 2)), desk.posted) })
    }

    @Test fun anIntentPostedLeavesAfterTheUnsaidTextAndItsPromiseSettlesWithItsReply(): Promise<Unit> {
        val desk = Desk()
        val area = desk.show("1", "abc")
        desk.writer.merged()
        val got = ArrayList<dynamic>()
        val refused = ArrayList<dynamic>()
        return play(
            { desk.reply() },
            {
                area.value = "abcd"   // typed, no event heard yet
                area.setSelectionRange(4, 4)
                desk.writer.post("take", Json.Object("ids" to Json.Array("2"))).then<Unit> { got.add(it) }
                assertEquals(write("1", "abcd"), desk.posted.last())
                assertEquals(2, desk.posted.size)   // the take waits for the write's reply
            },
            { desk.reply() },
            {
                assertEquals(take(Json.Object("ids" to Json.Array("2"))), desk.posted.last())
                assertTrue(got.isEmpty())
                desk.reply(js("({ok: 'taken'})"))
            },
            {
                assertEquals("taken", got.single().ok)
                desk.writer.post("take", Json.Object("ids" to Json.Array("3"))).then<Unit>({ got.add(it) }, { refused.add(it) })
                assertEquals(take(Json.Object("ids" to Json.Array("3"))), desk.posted.last())   // nothing unsaid: no write first
                desk.refuse(js("({data: {reason: 'held'}})"))
            },
            {
                assertEquals(1, got.size)
                assertEquals("held", refused.single().data.reason)
            })
    }

    @Test fun aParagraphBegunSaysWhatCameBeforeTakesAfterAndWhatIsTypedMeanwhileGoesOnInThePartBorn(): Promise<Unit> {
        val desk = Desk()
        val first = desk.show("1", "First.")
        desk.writer.merged()
        lateinit var born: HTMLTextAreaElement
        return play(
            { desk.reply() },
            { desk.type(first, "First.\n", "insertLineBreak") },   // one line broken: no paragraph yet
            { assertEquals(write("1", "First.\n"), desk.posted.last()); desk.reply() },
            {
                desk.type(first, "First.\n\n", "insertLineBreak")
                assertEquals(write("1", "First.", 6), desk.posted.last())   // the blank line not said
            },
            { desk.reply() },
            {
                assertEquals(take(Json.Object("after" to "1")), desk.posted.last())
                desk.type(first, "First.\n\nS")
                desk.type(first, "First.\n\nSec")
                assertEquals(4, desk.posted.size)   // the take pending: typing posts nothing
            },
            {
                assertEquals(4, desk.posted.size)
                born = desk.show("2", "")   // the frame: the part born, its textarea a new element
                desk.writer.merged()
                assertEquals("Sec", born.value)
                assertSame(born, document.activeElement)
                desk.reply()   // the take's reply
            },
            { assertEquals(write("2", "Sec", 3), desk.posted.last()); assertEquals(5, desk.posted.size) })
    }

    @Test fun noParagraphIsBegunInAFenceOnBlankLinesAloneOrOnAPaste(): Promise<Unit> {
        val desk = Desk()
        val area = desk.show("1", "")
        desk.writer.merged()
        return play(
            { desk.reply() },
            { desk.type(area, "```\ncode\n\n", "insertLineBreak") },
            { assertEquals(write("1", "```\ncode\n\n"), desk.posted.last()); desk.reply() },
            { desk.type(area, "\n\n", "insertLineBreak") },
            { assertEquals(write("1", "\n\n"), desk.posted.last()); desk.reply() },
            { desk.type(area, "A\n\nB", "insertFromPaste") },
            { assertEquals(write("1", "A\n\nB"), desk.posted.last()); desk.reply() },
            {
                assertEquals(4, desk.posted.size)
                assertTrue(desk.posted.none { it.startsWith("take") })
            })
    }

    @Test fun aTakeAfterRefusedLeavesTheTypingInTheSamePartTheBlankLineIncluded(): Promise<Unit> {
        val desk = Desk()
        val area = desk.show("1", "First.")
        desk.writer.merged()
        return play(
            { desk.reply() },
            { desk.type(area, "First.\n", "insertLineBreak") },
            { desk.reply() },
            { desk.type(area, "First.\n\n", "insertLineBreak") },
            { assertEquals(write("1", "First.", 6), desk.posted.last()); desk.reply() },
            {
                assertEquals(take(Json.Object("after" to "1")), desk.posted.last())
                desk.type(area, "First.\n\nSec")
                desk.refuse()
            },
            {
                assertEquals(write("1", "First.\n\nSec"), desk.posted.last())
                desk.reply()
                desk.type(area, "First.\n\nSecond")
            },
            { assertEquals(write("1", "First.\n\nSecond"), desk.posted.last()) })
    }

    @Test fun aFocusGoneElsewhereWritesWhatIsUnsaidThenReleasesOnce(): Promise<Unit> {
        val desk = Desk()
        val area = desk.show("1", "abc")
        desk.writer.merged()
        return play(
            { desk.reply() },
            {
                area.value = "abcd"
                area.setSelectionRange(4, 4)
                desk.button().focus()
                desk.focusout(area)
            },
            { assertEquals(write("1", "abcd"), desk.posted.last()); desk.reply() },
            { assertEquals(release("1"), desk.posted.last()); assertEquals(3, desk.posted.size) },
            {
                desk.focusout(area)
                desk.writer.leave()
                desk.type(area, "abcde")
                desk.keyup(area)
            },
            { desk.reply() },
            { assertEquals(listOf(write("1", "abc"), write("1", "abcd"), release("1")), desk.posted) })
    }

    @Test fun aFocusoutWhileTheTextareaKeepsTheFocusDoesNotRelease(): Promise<Unit> {
        val desk = Desk()
        val area = desk.show("1", "abc")
        desk.writer.merged()
        return play(
            { desk.reply() },
            { assertSame(area, document.activeElement); desk.focusout(area) },
            { },
            { assertEquals(listOf(write("1", "abc")), desk.posted) })
    }

    @Test fun aTabIdleLongerThanItsWaitReleasesAnInputRestartsTheWait(): Promise<Unit> {
        val desk = Desk(idle = 60)
        val area = desk.show("1", "abc")
        desk.writer.merged()
        desk.reply()
        return tick(40).then {
            desk.type(area, "abcd")   // 40 ms after the merge: the wait starts again
            desk.reply()
        }.then { tick(40) }.unsafeCast<Promise<Unit>>().then {
            assertEquals(listOf(write("1", "abc"), write("1", "abcd")), desk.posted)   // 80 ms since the merge, 40 since the input
        }.then { tick(50) }.unsafeCast<Promise<Unit>>().then {
            assertEquals(release("1"), desk.posted.last())
            assertEquals(3, desk.posted.size)
        }
    }

    @Test fun aTextareaGoneAfterAMergeIsHeardNoMoreAndANewOneIsAdopted(): Promise<Unit> {
        val desk = Desk()
        desk.show("1", "abc")
        desk.writer.merged()
        return play(
            { desk.reply() },
            {
                desk.root.innerHTML = "<textarea></textarea>"   // given back: shown as source, no part held
                desk.writer.merged()
                desk.type(desk.area(), "abcd")
                desk.keyup(desk.area())
            },
            { assertEquals(1, desk.posted.size) },
            {
                val area = desk.show("3", "xyz")
                desk.writer.merged()
                assertSame(area, document.activeElement)
                assertEquals(3, area.selectionStart)
                assertEquals(write("3", "xyz"), desk.posted.last())
                assertEquals(2, desk.posted.size)
            })
    }

    @Test fun anotherMergeOverTheSameTextareaDoesNothing(): Promise<Unit> {
        val desk = Desk()
        val area = desk.show("1", "abc")
        desk.writer.merged()
        return play(
            { desk.reply() },
            {
                area.setSelectionRange(1, 1)   // moved, no event heard: the merge must not put it back at the end
                desk.writer.merged()
                assertSame(area, document.activeElement)
                assertEquals(1, area.selectionStart)
            },
            { assertEquals(1, desk.posted.size) })
    }
}
