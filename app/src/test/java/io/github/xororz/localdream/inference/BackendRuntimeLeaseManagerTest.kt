package io.github.xororz.localdream.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendRuntimeLeaseManagerTest {
    @Test
    fun backendStopsOnlyAfterEveryServiceAndJobLeaseIsReleased() {
        val manager = BackendRuntimeLeaseManager()
        val api = manager.acquire("openai", BackendRuntimeLeaseManager.Kind.SERVICE)
        val mcp = manager.acquire("mcp", BackendRuntimeLeaseManager.Kind.SERVICE)
        val job = manager.acquire("mcp", BackendRuntimeLeaseManager.Kind.JOB)

        assertEquals(3, manager.snapshot().total)
        assertFalse(manager.canStopBackend())

        api.close()
        mcp.close()
        assertFalse(manager.canStopBackend())
        job.close()
        job.close()

        assertTrue(manager.canStopBackend())
    }
}
