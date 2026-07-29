package io.github.xororz.localdream.mcp

import android.content.Context
import android.content.Intent
import io.github.xororz.localdream.data.RuntimeProbeProjection
import io.github.xororz.localdream.data.RuntimeProbeStatus
import io.github.xororz.localdream.data.toProtectedProjection
import io.github.xororz.localdream.inference.InferenceDispatcher
import io.github.xororz.localdream.service.BackendService

/**
 * MCP projection of the product runtime lifecycle.  It intentionally exposes
 * state and model identity only: native commands, paths and runtime files are
 * never remotely addressable.
 */
interface McpRuntimeStore {
    fun status(): McpRuntimeStatus
    fun unload(runtimeId: String): McpRuntimeUnloadResult

    object Unavailable : McpRuntimeStore {
        override fun status() = McpRuntimeStatus(
            state = McpRuntimeState.IDLE,
            runtimeId = null,
            queuedTaskCount = 0,
            hasActiveTask = false,
            runtimeProbe = RuntimeProbeProjection(RuntimeProbeStatus.UNAVAILABLE, emptyList()),
        )
        override fun unload(runtimeId: String) = McpRuntimeUnloadResult.NOT_LOADED
    }
}

data class McpRuntimeStatus(
    val state: McpRuntimeState,
    val runtimeId: String?,
    val queuedTaskCount: Int,
    val hasActiveTask: Boolean,
    val runtimeProbe: RuntimeProbeProjection,
)

enum class McpRuntimeState(val wireValue: String) {
    IDLE("idle"),
    STARTING("starting"),
    RUNNING("running"),
    ERROR("error"),
}

enum class McpRuntimeUnloadResult {
    REQUESTED,
    NOT_LOADED,
    BUSY,
}

/**
 * Bridges MCP to the same BackendService lifecycle used by the local model
 * UI. The shared dispatcher serializes the stop admission with UI/OpenAI/MCP
 * inference so an unload cannot race an accepted native generation.
 */
class AndroidMcpRuntimeStore(
    private val context: Context,
    private val dispatcher: InferenceDispatcher = InferenceDispatcher.process,
) : McpRuntimeStore {
    override fun status(): McpRuntimeStatus = McpRuntimeStatus(
        state = when (BackendService.backendState.value) {
            BackendService.BackendState.Idle -> McpRuntimeState.IDLE
            BackendService.BackendState.Starting -> McpRuntimeState.STARTING
            BackendService.BackendState.Running -> McpRuntimeState.RUNNING
            is BackendService.BackendState.Error -> McpRuntimeState.ERROR
        },
        runtimeId = BackendService.servingModelId.value,
        queuedTaskCount = dispatcher.queuedTaskCount,
        hasActiveTask = dispatcher.hasActiveTask,
        runtimeProbe = BackendService.runtimeProbe.value.toProtectedProjection(),
    )

    override fun unload(runtimeId: String): McpRuntimeUnloadResult = dispatcher.tryRunRuntimeTransition {
        if (BackendService.servingModelId.value != runtimeId) return@tryRunRuntimeTransition McpRuntimeUnloadResult.NOT_LOADED
        context.startForegroundService(
            Intent(context, BackendService::class.java)
                .setAction(BackendService.ACTION_STOP)
                .putExtra(BackendService.EXTRA_EXPECTED_MODEL_ID, runtimeId),
        )
        McpRuntimeUnloadResult.REQUESTED
    } ?: McpRuntimeUnloadResult.BUSY
}
