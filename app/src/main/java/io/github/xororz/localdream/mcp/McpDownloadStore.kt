package io.github.xororz.localdream.mcp

import android.content.Context
import android.content.Intent
import io.github.xororz.localdream.data.ModelRepository
import io.github.xororz.localdream.service.ModelDownloadService
import kotlinx.coroutines.runBlocking

/**
 * MCP-facing view of the built-in model download catalogue. It deliberately
 * uses model IDs selected by the app and never accepts download URLs, paths,
 * archive metadata, or installer options from a remote client.
 */
data class McpDownload(
    val id: String,
    val name: String,
    val status: String,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
)

enum class McpDownloadCreateResult(val code: String) {
    ACCEPTED("ACCEPTED"),
    NOT_FOUND("DOWNLOAD_NOT_FOUND"),
    ALREADY_INSTALLED("DOWNLOAD_ALREADY_INSTALLED"),
    BUSY("DOWNLOAD_BUSY"),
    UNAVAILABLE("DOWNLOAD_UNAVAILABLE"),
}

/**
 * Bounded product-domain adapter for model download control. The Android
 * implementation retains the app's singleton foreground service, so MCP
 * cannot create concurrent transfers or choose arbitrary download inputs.
 */
interface McpDownloadStore {
    fun list(): List<McpDownload>
    fun create(modelId: String): McpDownloadCreateResult
    fun cancel(downloadId: String): Boolean

    object Unavailable : McpDownloadStore {
        override fun list(): List<McpDownload> = emptyList()
        override fun create(modelId: String): McpDownloadCreateResult = McpDownloadCreateResult.UNAVAILABLE
        override fun cancel(downloadId: String): Boolean = false
    }
}

class AndroidMcpDownloadStore(context: Context) : McpDownloadStore {
    private val appContext = context.applicationContext
    private val repository = ModelRepository.getInstance(appContext)

    override fun list(): List<McpDownload> = runBlocking {
        repository.ensureLoaded()
        val state = ModelDownloadService.downloadState.value
        repository.models
            .asSequence()
            .filter { !it.isCustom && it.fileUri.isNotBlank() }
            .map { model ->
                val active = state.modelIdOrNull() == model.id
                McpDownload(
                    id = model.id,
                    name = model.name,
                    status = when {
                        active -> state.mcpStatus()
                        model.isDownloaded -> "installed"
                        model.needsUpgrade -> "upgrade_available"
                        else -> "available"
                    },
                    downloadedBytes = (state as? ModelDownloadService.DownloadState.Downloading)
                        ?.takeIf { it.modelId == model.id }
                        ?.downloadedBytes,
                    totalBytes = (state as? ModelDownloadService.DownloadState.Downloading)
                        ?.takeIf { it.modelId == model.id }
                        ?.totalBytes,
                )
            }
            .toList()
    }

    override fun create(modelId: String): McpDownloadCreateResult = runBlocking {
        repository.ensureLoaded()
        val model = repository.models.firstOrNull { it.id == modelId && !it.isCustom && it.fileUri.isNotBlank() }
            ?: return@runBlocking McpDownloadCreateResult.NOT_FOUND
        if (model.isDownloaded && !model.needsUpgrade) return@runBlocking McpDownloadCreateResult.ALREADY_INSTALLED
        if (ModelDownloadService.downloadState.value.isBusy()) return@runBlocking McpDownloadCreateResult.BUSY
        try {
            model.startDownload(appContext)
            McpDownloadCreateResult.ACCEPTED
        } catch (_: RuntimeException) {
            McpDownloadCreateResult.UNAVAILABLE
        }
    }

    override fun cancel(downloadId: String): Boolean {
        val state = ModelDownloadService.downloadState.value
        if (!state.isBusy() || state.modelIdOrNull() != downloadId) return false
        appContext.startService(
            Intent(appContext, ModelDownloadService::class.java).apply {
                action = ModelDownloadService.ACTION_CANCEL_DOWNLOAD
                putExtra(ModelDownloadService.EXTRA_MODEL_ID, downloadId)
            },
        )
        return true
    }
}

private fun ModelDownloadService.DownloadState.modelIdOrNull(): String? = when (this) {
    is ModelDownloadService.DownloadState.Downloading -> modelId
    is ModelDownloadService.DownloadState.Extracting -> modelId
    is ModelDownloadService.DownloadState.Installing -> modelId
    is ModelDownloadService.DownloadState.Success -> modelId
    is ModelDownloadService.DownloadState.AlreadyInstalled -> modelId
    is ModelDownloadService.DownloadState.Error -> modelId
    ModelDownloadService.DownloadState.Idle -> null
}

private fun ModelDownloadService.DownloadState.mcpStatus(): String = when (this) {
    is ModelDownloadService.DownloadState.Downloading -> "downloading"
    is ModelDownloadService.DownloadState.Extracting -> "extracting"
    is ModelDownloadService.DownloadState.Installing -> "installing"
    is ModelDownloadService.DownloadState.Success -> "installed"
    is ModelDownloadService.DownloadState.AlreadyInstalled -> "installed"
    is ModelDownloadService.DownloadState.Cancelled -> "cancelled"
    is ModelDownloadService.DownloadState.Error -> "failed"
    ModelDownloadService.DownloadState.Idle -> "available"
}
