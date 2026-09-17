package com.republicate.kroom.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `validate` answers at save time what strict mode answers at render time: does this block read only
 * what its includer declares? Pinned on the cases where a naive "every `$name` in the source" scan
 * would cry wolf — block-local `%set`, loop variables and scope controls, the block's own defaults.
 */
class ValidationTest {

    @Test
    fun `a block reading only declared roots is valid`() {
        assertEquals(emptyList(), validate("## \$club.name\n\n%if(\$view.open)ouvert%end\n", setOf("club", "view")))
    }

    @Test
    fun `an undeclared root is reported at its first read`() {
        assertEquals(
            listOf(Problem("undeclared reference \$club", 3, 1)),
            validate("# Titre\n\n\$club.name\n", setOf("view"))
        )
    }

    @Test
    fun `block-local bindings, loop variables and scope controls are not roots`() {
        val source = "%set(\$label = \$club.name)\n%foreach(\$m in \$club.members)\n- \$foreach.count \$m.name \$label\n%end\n"
        assertEquals(emptyList(), validate(source, setOf("club")))
    }

    @Test
    fun `a set derived from an undeclared root still reports that root`() {
        val problems = validate("%set(\$label = \$club.name)\n\$label\n", emptySet())
        assertEquals(listOf("undeclared reference \$club"), problems.map { it.message })
    }

    @Test
    fun `a block's own default binds, its bare need falls to the includer`() {
        assertEquals(emptyList(), validate("%%@ tone: String = \"sobre\"\n\$tone\n", emptySet()))
        assertEquals(listOf("undeclared reference \$needed"), validate("%%@ needed: String\n\$needed\n", emptySet()).map { it.message })
    }

    @Test
    fun `a parse error is a positioned problem`() {
        val problems = validate("%if(\$x)\nno end\n", setOf("x"))
        assertEquals(1, problems.size)
        assertTrue(problems.single().line != null, problems.toString())
    }
}
