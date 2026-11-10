package com.republicate.kroom.webapp.velocity

import org.apache.velocity.engine.SafeText
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/** A value is data: a web page escapes it for where it lands, a `SafeText` is the markup that passes. */
class EscapingTest {

    private val dir: File = createTempDirectory("kroom-escaping").toFile().apply {
        deleteOnExit()
        resolve("page.html").writeText("""<p title="${'$'}v">${'$'}v</p>${'$'}m""")
        resolve("mail.txt").writeText("""Hello ${'$'}v""")
    }

    private fun plugin(vararg properties: Pair<String, Any?>) =
        VelocityPlugin(VelocityConfig().apply { devMode = true; devDir = dir; this.properties.putAll(properties) })

    private val model = mapOf("v" to """<b class="x">""", "m" to SafeText("<i>markup</i>"))

    @Test
    fun `a web page escapes a value for its position, a SafeText passes`() {
        assertEquals("""<p title="&lt;b class=&quot;x&quot;&gt;">&lt;b class=&quot;x&quot;&gt;</p><i>markup</i>""", plugin().render("page.html", model))
    }

    @Test
    fun `what is no web page is left as written`() {
        assertEquals("""Hello <b class="x">""", plugin().render("mail.txt", model))
    }

    @Test
    fun `an application names its own handler, or none`() {
        assertEquals("""<p title="<b class="x">"><b class="x"></p><i>markup</i>""", plugin("event_handler.reference_insertion.class" to "").render("page.html", model))
    }
}
