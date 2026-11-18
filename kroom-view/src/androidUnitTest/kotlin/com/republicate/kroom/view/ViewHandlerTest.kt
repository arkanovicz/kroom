package com.republicate.kroom.view

import org.apache.velocity.runtime.RuntimeServices
import org.apache.velocity.runtime.resource.ResourceManagerImpl
import kotlin.test.Test
import kotlin.test.assertEquals

class ViewHandlerTest {

    @Test
    fun velocityStartsWithTheFirstTemplate() {
        assertEquals("plain", ViewHandler.serve("hello.txt").decodeToString().trim())
        assertEquals(0, StartedResourceManager.started)

        assertEquals("<main>hello</main>", ViewHandler.serve("index").decodeToString().trim())
        assertEquals(1, StartedResourceManager.started)

        ViewHandler.serve("index")
        assertEquals(1, StartedResourceManager.started)
    }
}

/** Named in the test's velocity.properties: counts the engine's starts. */
class StartedResourceManager : ResourceManagerImpl() {

    override fun initialize(rs: RuntimeServices) {
        started++
        super.initialize(rs)
    }

    companion object {
        var started = 0
    }
}
