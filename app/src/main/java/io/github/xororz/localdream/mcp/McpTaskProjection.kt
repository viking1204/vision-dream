package io.github.xororz.localdream.mcp

import io.github.xororz.localdream.data.InferenceJobStatus

/** MCP Task 是同一 InferenceJob 的只读协议投影，不创建独立任务记录。 */
object McpTaskProjection {
    fun from(status: InferenceJobStatus): McpTaskState = when (status) {
        InferenceJobStatus.QUEUED, InferenceJobStatus.RUNNING -> McpTaskState.WORKING
        InferenceJobStatus.SUCCEEDED -> McpTaskState.COMPLETED
        InferenceJobStatus.FAILED -> McpTaskState.FAILED
        InferenceJobStatus.CANCELLED -> McpTaskState.CANCELLED
        InferenceJobStatus.UNKNOWN -> McpTaskState.UNKNOWN
    }
}

enum class McpTaskState(val wireValue: String) {
    WORKING("working"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    UNKNOWN("unknown"),
}
