package io.github.xororz.localdream.service

import io.github.xororz.localdream.inference.BackendRuntimeLeaseManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiApiServiceShutdownPolicyTest {
    @Test
    fun `stops when no lease remains`() {
        assertTrue(
            shouldStopBackendAfterOwnerCancellation(
                BackendRuntimeLeaseManager.Snapshot(
                    total = 0,
                    services = 0,
                    jobs = 0,
                    owners = emptySet(),
                ),
                stoppedOwnerId = "openai-api",
            ),
        )
    }

    @Test
    fun `force stops when only the cancelled owner is still unwinding`() {
        assertTrue(
            shouldStopBackendAfterOwnerCancellation(
                BackendRuntimeLeaseManager.Snapshot(
                    total = 1,
                    services = 0,
                    jobs = 1,
                    owners = setOf("openai-api"),
                ),
                stoppedOwnerId = "openai-api",
            ),
        )
    }

    @Test
    fun `keeps backend when another transport still owns it`() {
        assertFalse(
            shouldStopBackendAfterOwnerCancellation(
                BackendRuntimeLeaseManager.Snapshot(
                    total = 2,
                    services = 1,
                    jobs = 1,
                    owners = setOf("openai-api", "mcp"),
                ),
                stoppedOwnerId = "openai-api",
            ),
        )
    }
}
