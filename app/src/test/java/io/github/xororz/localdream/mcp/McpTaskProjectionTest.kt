package io.github.xororz.localdream.mcp

import io.github.xororz.localdream.data.InferenceJobStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class McpTaskProjectionTest {
    @Test
    fun projectsEveryKnownJobStateToItsMcpTaskState() {
        assertEquals(McpTaskState.WORKING, McpTaskProjection.from(InferenceJobStatus.QUEUED))
        assertEquals(McpTaskState.WORKING, McpTaskProjection.from(InferenceJobStatus.RUNNING))
        assertEquals(McpTaskState.COMPLETED, McpTaskProjection.from(InferenceJobStatus.SUCCEEDED))
        assertEquals(McpTaskState.FAILED, McpTaskProjection.from(InferenceJobStatus.FAILED))
        assertEquals(McpTaskState.CANCELLED, McpTaskProjection.from(InferenceJobStatus.CANCELLED))
        assertEquals(McpTaskState.UNKNOWN, McpTaskProjection.from(InferenceJobStatus.UNKNOWN))
    }
}
