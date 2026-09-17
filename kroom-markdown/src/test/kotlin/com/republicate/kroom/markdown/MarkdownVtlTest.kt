package com.republicate.kroom.markdown

import org.apache.velocity.engine.Config
import org.apache.velocity.engine.VelocityContext
import org.apache.velocity.engine.VelocityEngine
import org.apache.velocity.engine.jvm.ScriptingCompiler
import org.apache.velocity.engine.parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The `%` dialect is a BUILD-TIME fact: the sigils are substituted into the grammar template and baked
 * into the generated lexer's ATN, where nothing at runtime can contradict them. These tests pin the two
 * halves of that trade — every directive answers to `%`, and `#` is given back to Markdown — plus the
 * pieces a document author actually leans on: the `%%@` header with its defaults, and `%*…*%` comments.
 *
 * A regeneration that silently fell back to the stock lexer would still compile and still render most
 * templates; only `#` going live again would tell. Hence the last test.
 */
class MarkdownVtlTest {

    private fun engine() = VelocityEngine(ScriptingCompiler(), config = Config(lexerSource = MarkdownVtl))

    private fun render(source: String, bindings: Map<String, Any?> = emptyMap()): String =
        engine().compile(source).merge(VelocityContext(bindings.toMutableMap()))

    @Test
    fun `directives answer to the percent sigil`() {
        assertEquals("yes", render("%if(\$ok)yes%{else}no%end", mapOf("ok" to true)))
        assertEquals("abc", render("%foreach(\$c in \$list)\$c%end", mapOf("list" to listOf("a", "b", "c"))))
        assertEquals("2", render("%set(\$n = 2)\$n"))
    }

    @Test
    fun `a markdown document passes through byte-identical`() {
        val md = "# Heading\n\n## Sub\n\nsee [link](page#anchor)\n"
        assertEquals(md, render(md))
    }

    @Test
    fun `the header types the context roots and its default fills an absent one`() {
        val declared = parse("%%@ name: String = \"world\"\nhi \$name", Config(lexerSource = MarkdownVtl)).header
        assertEquals(listOf("name"), declared.map { it.name })
        assertEquals("String", declared.single().type)
        assertNotNull(declared.single().default, "the default expression must survive the parse")

        assertEquals("hi world", render("%%@ name: String = \"world\"\nhi \$name"))
        assertEquals("hi kroom", render("%%@ name: String = \"world\"\nhi \$name", mapOf("name" to "kroom")))
    }

    @Test
    fun `comments are dropped`() {
        assertEquals("ab", render("a%* editorial note *%b"))
    }

    @Test
    fun `the pin has teeth - the stock sigil is inert text`() {
        // under the stock lexer this renders "hidden"; here it is prose down to the last character.
        // Written without a reference on purpose: `$` is the one sigil that does NOT move.
        assertEquals("#if(true)hidden#end", render("#if(true)hidden#end"))
    }
}
