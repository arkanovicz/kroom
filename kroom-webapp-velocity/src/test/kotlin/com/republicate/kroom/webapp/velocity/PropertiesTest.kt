package com.republicate.kroom.webapp.velocity

import kotlin.test.Test
import kotlin.test.assertEquals

/** The open door: an application's engine properties reach the engine, over kroom's defaults. */
class PropertiesTest {

    private fun engineWith(vararg properties: Pair<String, Any?>) =
        VelocityPlugin(VelocityConfig().apply { templatePath = null; this.properties.putAll(properties) }).engine

    @Test
    fun `an application property reaches the engine`() {
        assertEquals("/data/content", engineWith("markdown.resource.loader.file.path" to "/data/content").getProperty("markdown.resource.loader.file.path"))
    }

    @Test
    fun `an application property overrides a kroom default`() {
        assertEquals("false", engineWith("velocimacro.library.autoreload" to "false").getProperty("velocimacro.library.autoreload").toString())
        assertEquals("classpath", engineWith().getProperty("resource.loaders").let { (it as? List<*>)?.joinToString(",") ?: it.toString() })
    }
}
