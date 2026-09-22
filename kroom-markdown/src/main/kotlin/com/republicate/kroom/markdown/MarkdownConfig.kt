package com.republicate.kroom.markdown

import org.apache.velocity.engine.Config
import org.apache.velocity.engine.ResourceLoader
import org.apache.velocity.engine.ResourceNotFoundException
import org.apache.velocity.engine.runtime.introspection.Sandbox

/**
 * Everything the `%` stack is configured by. Blocks are user-authored, so the defaults watch them: strict
 * references (which also enforces a block's header contract), the full sandbox with no writes into the
 * application's objects, and of VTL's 2.x conveniences only the one prose needs — `$club.name` unbraced in
 * running text. The application relaxes any of it here, or through `markdown.*` properties
 * ([fromProperties]).
 */
data class MarkdownConfig(
    /** Where blocks are read from — typically the same store the editor writes through. */
    val loader: ResourceLoader = ResourceLoader { throw ResourceNotFoundException("no markdown loader configured for \"$it\"") },

    /** The sandbox's rules, in velocity's ACL syntax; null runs blocks unsandboxed. */
    val acl: String? = Sandbox.DEFAULT_ACL + "\n- write *",

    /** Page-context tools every block may use, by name; a block sees nothing else but its arguments. */
    val tools: List<String> = emptyList(),

    /** A `#` template of the application's decorating every block (edit affordances), or none. */
    val wrapper: String? = null,

    /** What an unwritten block shows: a `%` snippet, `$name` in scope. */
    val missing: String = "*No content for **\$name**.*",

    /** Engine settings, as velocity 3.0 names them; the lexer is forced afterwards, whatever this says. */
    val engine: Config = Config(strictReferences = true, informalNavigation = true),
) {
    companion object {
        /**
         * The `markdown.*` properties of a host engine, prefix stripped. kroom's own keys — `loader` (an
         * instance), `acl`, `sandbox` (`false` to disable), `tools`, `block.wrapper`, `missing` — are read
         * here; every other key goes to velocity's [Config.fromProperties], over this module's defaults.
         */
        @JvmStatic
        fun fromProperties(properties: Map<String, Any?>): MarkdownConfig {
            val defaults = MarkdownConfig()
            val own = setOf(LOADER, ACL, SANDBOX, TOOLS, WRAPPER, MISSING)
            return MarkdownConfig(
                loader = properties[LOADER] as? ResourceLoader ?: defaults.loader,
                acl = when {
                    properties[SANDBOX]?.toString() == "false" -> null
                    else -> properties[ACL]?.toString() ?: defaults.acl
                },
                tools = properties[TOOLS]?.let { names(it) } ?: defaults.tools,
                wrapper = properties[WRAPPER]?.toString(),
                missing = properties[MISSING]?.toString() ?: defaults.missing,
                engine = Config.fromProperties(properties.filterKeys { it !in own }, defaults.engine),
            )
        }

        const val LOADER = "loader"
        const val ACL = "acl"
        const val SANDBOX = "sandbox"
        const val TOOLS = "tools"
        const val WRAPPER = "block.wrapper"
        const val MISSING = "missing"

        private fun names(value: Any): List<String> = when (value) {
            is Collection<*> -> value.map { it.toString().trim() }
            else -> value.toString().split(',').map { it.trim() }
        }.filter { it.isNotEmpty() }
    }
}
