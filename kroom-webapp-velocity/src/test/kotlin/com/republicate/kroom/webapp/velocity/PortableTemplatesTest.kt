package com.republicate.kroom.webapp.velocity

import org.apache.velocity.VelocityContext
import org.apache.velocity.app.VelocityEngine
import org.apache.velocity.runtime.RuntimeConstants
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Whatever kroom itself ships must render the same on every pipeline. `VelocityPlugin` runs the
 * classic facade, where the 2.x compat flags are on; the build-time pipelines (compiled, inline)
 * start from a pure 3.0 `Config` where they are off. The difference is silent — informal `$a.b` in
 * free text renders as the root followed by literal `.b` rather than failing — so kroom's own
 * templates are formal-only, pinned here with the flags explicitly off.
 *
 * A consumer's templates are the consumer's call: they configure their own pipeline. kroom only
 * guarantees its own side is neutral.
 */
class PortableTemplatesTest {

    private fun pureEngine() = VelocityEngine().apply {
        setProperty(RuntimeConstants.INPUT_ENCODING, "UTF-8")
        setProperty(RuntimeConstants.RESOURCE_LOADERS, "classpath")
        setProperty("resource.loader.classpath.class", ClasspathResourceLoader::class.java.name)
        setProperty(RuntimeConstants.VM_LIBRARY, "kroom-macros.vtl")
        for (flag in listOf("informal_navigation", "duck_typing", "elvis_falsy", "string_escapes", "introspection")) {
            setProperty("compat.$flag", false)
        }
        init()
    }

    private fun render(source: String): String {
        val context = VelocityContext(mapOf("versions" to mapOf("/js/app.js" to "7"), "user" to "alice"))
        return StringWriter().also { pureEngine().evaluate(context, it, "test", source) }.toString()
    }

    @Test
    fun `the shipped macro library renders without the compat flags`() {
        assertEquals("/js/app.js?v=7", render("""#versioned("/js/app.js")"""))
    }

    @Test
    fun `the shipped page templates render without the compat flags`() {
        assertEquals("src:alice", render("""#parse("pages/source.html")"""))
        assertEquals("terms:alice", render("""#parse("pages/legal/terms.html")"""))
    }

    @Test
    fun `the pin has teeth - informal navigation is off and degrades silently`() {
        // Not an error: the root resolves and `.name` stays literal text. This is why kroom writes
        // formal references rather than relying on either pipeline's default.
        assertEquals("alice.length", render("\$user.length"))
        assertEquals("5", render("\${user.length}"))
    }
}
