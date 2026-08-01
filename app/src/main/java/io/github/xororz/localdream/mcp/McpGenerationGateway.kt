package io.github.xororz.localdream.mcp

import android.content.Context
import io.github.xororz.localdream.data.AssetOrigin
import io.github.xororz.localdream.data.GenerationMode
import io.github.xororz.localdream.data.GenerationPreferences
import io.github.xororz.localdream.data.HistoryFilter
import io.github.xororz.localdream.data.HistoryItem
import io.github.xororz.localdream.data.HistoryManager
import io.github.xororz.localdream.data.InferenceHistoryAssociation
import io.github.xororz.localdream.data.InferenceJobStatus
import io.github.xororz.localdream.data.PerformancePresetConfig
import io.github.xororz.localdream.data.PerformancePresetRepository
import io.github.xororz.localdream.data.PromptRepository
import io.github.xororz.localdream.data.RoomInferenceJobRepository
import io.github.xororz.localdream.data.db.AppDatabase
import io.github.xororz.localdream.data.db.HistoryDao
import io.github.xororz.localdream.data.requireExecutableSnapshot
import io.github.xororz.localdream.inference.InferenceDispatcher
import io.github.xororz.localdream.openai.BackendRuntimeCoordinator
import io.github.xororz.localdream.openai.BoundedSerialExecutor
import io.github.xororz.localdream.openai.ImageRequestParameters
import io.github.xororz.localdream.openai.InstalledModelCatalog
import io.github.xororz.localdream.openai.NativeBackendClient
import io.github.xororz.localdream.openai.validateParameters
import io.github.xororz.localdream.service.NativeRuntimeAttestationRecorder
import io.github.xororz.localdream.ui.screens.GenerationParameters
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * MCP generation has its own protocol boundary but records the same durable
 * Job and history facts as the local product. It never delegates to OpenAI.
 */
class McpGenerationGateway(
    private val jobs: McpJobStore,
    private val scheduler: McpGenerationScheduler,
    private val models: McpInstalledModelCatalog = McpInstalledModelCatalog.Unavailable,
    private val prompts: McpPromptStore = McpPromptStore.Unavailable,
    private val presets: McpPresetStore = McpPresetStore.Unavailable,
    private val assets: McpAssetStore = McpAssetStore.Unavailable,
    private val downloads: McpDownloadStore = McpDownloadStore.Unavailable,
    private val cancellations: McpGenerationCanceller = McpGenerationCanceller.Unavailable,
    private val runtime: McpRuntimeStore = McpRuntimeStore.Unavailable,
    private val clients: McpClientManagementStore = McpClientManagementStore.Unavailable,
) : McpToolGateway {
    /**
     * Keep protocol capability discovery tied to the concrete product-domain
     * adapters below.  Registering a future tool in [McpToolRegistry] alone
     * must never make it visible to a client before its domain operation is
     * safe and implemented.
     */
    override fun supports(definition: McpToolDefinition): Boolean = definition.name in SUPPORTED_TOOLS &&
        (definition.name !in ASSET_TOOLS || assets !== McpAssetStore.Unavailable) &&
        (definition.name !in DOWNLOAD_TOOLS || downloads !== McpDownloadStore.Unavailable) &&
        (definition.name !in RUNTIME_TOOLS || runtime !== McpRuntimeStore.Unavailable) &&
        (definition.name !in CLIENT_TOOLS || clients !== McpClientManagementStore.Unavailable)

    override fun execute(
        client: McpAuthenticatedClient,
        invocation: McpToolInvocation,
        arguments: JSONObject,
    ): McpToolGatewayResult = when (invocation.definition.name) {
        "models.list" -> listModels()
        "models.get" -> getModel(arguments)
        "generation.create" -> create(client, arguments)
        "jobs.get" -> get(client, arguments)
        "jobs.list" -> listJobs(client)
        "jobs.cancel" -> cancelJob(client, arguments)
        "presets.list" -> listPresets()
        "presets.get" -> getPreset(arguments)
        "presets.create" -> createPreset(arguments)
        "presets.update" -> updatePreset(arguments)
        "presets.export" -> exportPresets()
        "presets.import" -> importPresets(arguments)
        "presets.delete" -> deletePreset(arguments)
        "prompts.list" -> listPrompts()
        "prompts.get" -> getPrompt(arguments)
        "prompts.create" -> createPrompt(arguments)
        "prompts.update" -> updatePrompt(arguments)
        "prompts.delete" -> deletePrompt(arguments)
        "assets.list" -> listAssets()
        "assets.delete" -> deleteAsset(arguments)
        "downloads.list" -> listDownloads()
        "downloads.create" -> createDownload(arguments)
        "downloads.cancel" -> cancelDownload(arguments)
        "runtime.status" -> runtimeStatus()
        "runtime.unload" -> unloadRuntime(arguments)
        "client.revoke" -> revokeClient(arguments)
        "token.rotate" -> rotateClientToken(arguments)
        else -> McpToolGatewayResult.Rejected("TOOL_UNAVAILABLE")
    }

    /**
     * Installed-model metadata is deliberately projected instead of exposing a
     * storage directory or any runtime command. The Tool contract remains a
     * read-only product catalog even though the underlying catalog validates
     * filesystem-backed installations.
     */
    private fun listModels(): McpToolGatewayResult = McpToolGatewayResult.Completed(
        JSONObject().put("models", org.json.JSONArray(models.all().map(::modelJson))),
    )

    private fun getModel(arguments: JSONObject): McpToolGatewayResult {
        val model = models.find(arguments.optString("modelId"))
            ?: return McpToolGatewayResult.Rejected("MODEL_NOT_FOUND")
        return McpToolGatewayResult.Completed(modelJson(model))
    }

    private fun create(client: McpAuthenticatedClient, arguments: JSONObject): McpToolGatewayResult {
        val modelId = arguments.optString("modelId").trim()
        val prompt = arguments.optString("prompt").trim()
        if (modelId.isEmpty() || prompt.isEmpty()) return McpToolGatewayResult.Rejected("INVALID_PARAMS")
        val parameters = try {
            generationParameters(modelId, prompt, arguments)
        } catch (_: IllegalArgumentException) {
            return McpToolGatewayResult.Rejected("INVALID_PARAMS")
        } catch (_: io.github.xororz.localdream.openai.OpenAiRequestException) {
            return McpToolGatewayResult.Rejected("INVALID_PARAMS")
        }
        val job = try {
            jobs.accept(
                ownerId = client.clientId,
                modelId = modelId,
                explicitPresetId = arguments.optString("presetId").takeIf(String::isNotBlank),
            )
        } catch (_: IllegalArgumentException) {
            return McpToolGatewayResult.Rejected("PRESET_INCOMPATIBLE")
        } catch (_: IllegalStateException) {
            return McpToolGatewayResult.Rejected("PRESET_INCOMPATIBLE")
        }
        val scheduled = scheduler.submit(
            McpGenerationRequest(job, parameters),
        )
        if (scheduled != McpGenerationScheduleResult.ACCEPTED) {
            jobs.discard(job.id)
            return McpToolGatewayResult.Rejected(scheduled.code)
        }
        return McpToolGatewayResult.Completed(
            JSONObject().put("jobId", job.id).put("task", McpTaskState.WORKING.wireValue),
            job.id,
        )
    }

    /**
     * Keep W7's observable fixture fields on the accepted request. MCP must
     * not silently inherit mutable UI defaults, otherwise it cannot be fairly
     * compared with the equivalent `/v1` request.
     */
    private fun generationParameters(modelId: String, prompt: String, arguments: JSONObject): ImageRequestParameters {
        val width = arguments.optionalPositiveInt("width")
        val height = arguments.optionalPositiveInt("height")
        require((width == null) == (height == null)) { "width and height must be supplied together" }
        val defaults = ImageRequestParameters(
            modelId = modelId,
            prompt = prompt,
            negativePrompt = arguments.optString("negativePrompt"),
        )
        return defaults.copy(
            width = width,
            height = height,
            steps = arguments.optionalPositiveInt("steps") ?: defaults.steps,
            cfg = arguments.optionalFiniteFloat("cfg") ?: defaults.cfg,
            seed = arguments.optionalLong("seed"),
            scheduler = arguments.optString("scheduler").takeIf(String::isNotBlank) ?: defaults.scheduler,
            denoiseStrength = arguments.optionalFiniteFloat("denoiseStrength") ?: defaults.denoiseStrength,
        ).also { validateParameters(it, requiresImage = false) }
    }

    private fun get(client: McpAuthenticatedClient, arguments: JSONObject): McpToolGatewayResult {
        val job = jobs.get(arguments.optString("jobId"))
            ?.takeIf { it.ownerId == client.clientId }
            ?: return McpToolGatewayResult.Rejected("JOB_NOT_FOUND")
        val result = JSONObject()
            .put("jobId", job.id)
            .put("task", McpTaskProjection.from(job.status).wireValue)
        if (job.status == InferenceJobStatus.SUCCEEDED) {
            val asset = jobs.historyAssetFor(job.id) ?: return McpToolGatewayResult.Rejected("INFERENCE_FAILED")
            val imagePath = McpProtocol.assetPath(asset.assetId)
            // Jobs expose a stable authenticated asset resource. The Job id
            // remains task metadata and never becomes part of the asset route.
            result.put("image", imagePath)
            result.put(
                "content",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("type", "resource_link")
                        .put("uri", imagePath)
                        .put("mimeType", asset.mimeType),
                ),
            )
        }
        return McpToolGatewayResult.Completed(result, job.id)
    }

    private fun listJobs(client: McpAuthenticatedClient): McpToolGatewayResult = McpToolGatewayResult.Completed(
        JSONObject().put(
            "jobs",
            org.json.JSONArray(
                jobs.listFor(client.clientId).map { job ->
                    JSONObject()
                        .put("jobId", job.id)
                        .put("task", McpTaskProjection.from(job.status).wireValue)
                },
            ),
        ),
    )

    /**
     * A client can cancel only its own non-terminal Job. The scheduler owner is
     * the Job ID (rather than the client ID), so this never cancels another
     * request that happens to belong to the same MCP client.
     */
    private fun cancelJob(client: McpAuthenticatedClient, arguments: JSONObject): McpToolGatewayResult {
        val job = jobs.get(arguments.optString("jobId"))
            ?.takeIf { it.ownerId == client.clientId }
            ?: return McpToolGatewayResult.Rejected("JOB_NOT_FOUND")
        if (job.status !in setOf(InferenceJobStatus.QUEUED, InferenceJobStatus.RUNNING)) {
            return McpToolGatewayResult.Completed(
                JSONObject().put("jobId", job.id).put("task", McpTaskProjection.from(job.status).wireValue),
                job.id,
            )
        }
        if (!cancellations.cancel(job.id)) return McpToolGatewayResult.Rejected("JOB_NOT_CANCELLABLE")
        jobs.updateStatus(job.id, InferenceJobStatus.CANCELLED)
        McpTaskEventBus.publish(McpTaskEventBus.Event(client.clientId, job.id, InferenceJobStatus.CANCELLED))
        return McpToolGatewayResult.Completed(
            JSONObject().put("jobId", job.id).put("task", McpTaskState.CANCELLED.wireValue),
            job.id,
        )
    }

    private fun modelJson(model: McpInstalledModel): JSONObject = JSONObject()
        .put("id", model.id)
        .put("name", model.name)
        .put("kind", model.kind)
        .put("backendType", model.backendType)
        .put("generationSize", model.generationSize)
        .put("supportsImageInput", model.supportsImageInput)

    private fun listPresets(): McpToolGatewayResult = McpToolGatewayResult.Completed(
        JSONObject().put("presets", org.json.JSONArray(presets.list().map(::presetJson))),
    )

    private fun getPreset(arguments: JSONObject): McpToolGatewayResult = presetId(arguments)
        ?.let(presets::get)
        ?.let { McpToolGatewayResult.Completed(presetJson(it)) }
        ?: McpToolGatewayResult.Rejected("PRESET_NOT_FOUND")

    private fun createPreset(arguments: JSONObject): McpToolGatewayResult = try {
        McpToolGatewayResult.Completed(
            presetJson(
                presets.create(
                    arguments.optString("name"),
                    arguments.optString("selector"),
                    arguments.optString("configJson"),
                ),
            ),
        )
    } catch (_: IllegalArgumentException) {
        McpToolGatewayResult.Rejected("INVALID_PARAMS")
    } catch (_: IllegalStateException) {
        McpToolGatewayResult.Rejected("TOOL_UNAVAILABLE")
    }

    private fun updatePreset(arguments: JSONObject): McpToolGatewayResult {
        val id = presetId(arguments) ?: return McpToolGatewayResult.Rejected("PRESET_NOT_FOUND")
        val current = presets.get(id) ?: return McpToolGatewayResult.Rejected("PRESET_NOT_FOUND")
        return try {
            McpToolGatewayResult.Completed(
                presetJson(
                    presets.update(
                        id = id,
                        revision = arguments.optLong("revision"),
                        name = arguments.optString("name", current.name),
                        selector = arguments.optString("selector", current.selector),
                        configJson = arguments.optString("configJson", current.configJson),
                    ),
                ),
            )
        } catch (error: IllegalArgumentException) {
            McpToolGatewayResult.Rejected(
                if (error.message?.contains("revision conflict") == true) "PRESET_REVISION_CONFLICT" else "INVALID_PARAMS",
            )
        } catch (_: IllegalStateException) {
            McpToolGatewayResult.Rejected("TOOL_UNAVAILABLE")
        }
    }

    private fun deletePreset(arguments: JSONObject): McpToolGatewayResult {
        val id = presetId(arguments) ?: return McpToolGatewayResult.Rejected("PRESET_NOT_FOUND")
        if (presets.get(id) == null) return McpToolGatewayResult.Rejected("PRESET_NOT_FOUND")
        val result = presets.delete(id)
        return if (!result.deleted) {
            McpToolGatewayResult.Rejected("PRESET_PROTECTED")
        } else {
            McpToolGatewayResult.Completed(
                JSONObject()
                    .put("presetId", id)
                    .put("deleted", true)
                    .put("reboundBindingKeys", org.json.JSONArray(result.reboundBindingKeys)),
            )
        }
    }

    private fun exportPresets(): McpToolGatewayResult = try {
        McpToolGatewayResult.Completed(JSONObject().put("envelope", presets.exportEnvelope()))
    } catch (_: IllegalStateException) {
        McpToolGatewayResult.Rejected("TOOL_UNAVAILABLE")
    }

    private fun importPresets(arguments: JSONObject): McpToolGatewayResult = try {
        McpToolGatewayResult.Completed(
            JSONObject().put("presets", org.json.JSONArray(presets.importEnvelope(arguments.optString("envelope")).map(::presetJson))),
        )
    } catch (_: IllegalArgumentException) {
        McpToolGatewayResult.Rejected("INVALID_PARAMS")
    } catch (_: IllegalStateException) {
        McpToolGatewayResult.Rejected("TOOL_UNAVAILABLE")
    }

    private fun presetId(arguments: JSONObject): String? = arguments.optString("presetId").takeIf(String::isNotBlank)

    private fun presetJson(preset: io.github.xororz.localdream.data.PerformancePreset): JSONObject = JSONObject()
        .put("presetId", preset.id)
        .put("name", preset.name)
        .put("selector", preset.selector)
        .put("configJson", preset.configJson)
        .put("revision", preset.revision)
        .put(
            "kind",
            when {
                preset.isFallback -> "COMPATIBILITY_FALLBACK"
                preset.isBuiltIn -> "BUILT_IN"
                else -> "USER"
            },
        )

    /** Prompt templates remain product-owned records; MCP never accepts a path or shell command. */
    private fun listPrompts(): McpToolGatewayResult = McpToolGatewayResult.Completed(
        JSONObject().put("prompts", org.json.JSONArray(prompts.list().map(::promptJson))),
    )

    private fun getPrompt(arguments: JSONObject): McpToolGatewayResult = promptId(arguments)
        ?.let(prompts::get)
        ?.let { McpToolGatewayResult.Completed(promptJson(it)) }
        ?: McpToolGatewayResult.Rejected("PROMPT_NOT_FOUND")

    private fun createPrompt(arguments: JSONObject): McpToolGatewayResult = try {
        McpToolGatewayResult.Completed(
            promptJson(
                prompts.create(
                    title = arguments.optString("title"),
                    prompt = arguments.optString("prompt"),
                    negativePrompt = arguments.optString("negativePrompt"),
                ),
            ),
        )
    } catch (_: IllegalArgumentException) {
        McpToolGatewayResult.Rejected("INVALID_PARAMS")
    }

    private fun updatePrompt(arguments: JSONObject): McpToolGatewayResult {
        val id = promptId(arguments) ?: return McpToolGatewayResult.Rejected("PROMPT_NOT_FOUND")
        val current = prompts.get(id) ?: return McpToolGatewayResult.Rejected("PROMPT_NOT_FOUND")
        return try {
            val updated = prompts.update(
                id = id,
                title = arguments.optString("title", current.title),
                prompt = arguments.optString("prompt", current.prompt),
                negativePrompt = arguments.optString("negativePrompt", current.negativePrompt),
            ) ?: return McpToolGatewayResult.Rejected("PROMPT_NOT_FOUND")
            McpToolGatewayResult.Completed(promptJson(updated))
        } catch (_: IllegalArgumentException) {
            McpToolGatewayResult.Rejected("INVALID_PARAMS")
        }
    }

    private fun deletePrompt(arguments: JSONObject): McpToolGatewayResult {
        val id = promptId(arguments) ?: return McpToolGatewayResult.Rejected("PROMPT_NOT_FOUND")
        if (!prompts.delete(id)) return McpToolGatewayResult.Rejected("PROMPT_NOT_FOUND")
        return McpToolGatewayResult.Completed(JSONObject().put("promptId", id).put("deleted", true))
    }

    /**
     * Assets are existing local history records, not arbitrary paths.  Their
     * projection deliberately excludes prompt text and storage locations;
     * clients can only refer back to the server-issued history asset id.
     */
    private fun listAssets(): McpToolGatewayResult = McpToolGatewayResult.Completed(
        JSONObject().put("assets", org.json.JSONArray(assets.list().map(::assetJson))),
    )

    private fun deleteAsset(arguments: JSONObject): McpToolGatewayResult {
        val assetId = arguments.optString("assetId")
        if (!assetId.startsWith(HISTORY_ASSET_PREFIX) || assetId.removePrefix(HISTORY_ASSET_PREFIX).toLongOrNull() == null) {
            return McpToolGatewayResult.Rejected("ASSET_NOT_FOUND")
        }
        if (!assets.delete(assetId)) return McpToolGatewayResult.Rejected("ASSET_NOT_FOUND")
        return McpToolGatewayResult.Completed(JSONObject().put("assetId", assetId).put("deleted", true))
    }

    private fun assetJson(asset: McpAsset): JSONObject = JSONObject()
        .put("assetId", asset.id)
        .put("modelId", asset.modelId)
        .put("mimeType", asset.mimeType)
        .put("timestamp", asset.timestamp)
        .put("width", asset.width)
        .put("height", asset.height)
        .put("favorite", asset.favorite)

    /** The download projection is catalogue state only; URLs and local paths never cross MCP. */
    private fun listDownloads(): McpToolGatewayResult = McpToolGatewayResult.Completed(
        JSONObject().put("downloads", org.json.JSONArray(downloads.list().map(::downloadJson))),
    )

    private fun createDownload(arguments: JSONObject): McpToolGatewayResult = when (val result = downloads.create(arguments.optString("modelId"))) {
        McpDownloadCreateResult.ACCEPTED -> McpToolGatewayResult.Completed(
            JSONObject().put("downloadId", arguments.getString("modelId")).put("status", "queued"),
        )

        else -> McpToolGatewayResult.Rejected(result.code)
    }

    private fun cancelDownload(arguments: JSONObject): McpToolGatewayResult {
        val downloadId = arguments.optString("downloadId")
        if (!downloads.cancel(downloadId)) return McpToolGatewayResult.Rejected("DOWNLOAD_NOT_FOUND")
        return McpToolGatewayResult.Completed(JSONObject().put("downloadId", downloadId).put("cancelRequested", true))
    }

    private fun downloadJson(download: McpDownload): JSONObject = JSONObject()
        .put("downloadId", download.id)
        .put("modelId", download.id)
        .put("name", download.name)
        .put("status", download.status)
        .apply {
            download.downloadedBytes?.let { put("downloadedBytes", it) }
            download.totalBytes?.let { put("totalBytes", it) }
        }

    /** Runtime facts are a compact state projection, never a native command or file path. */
    private fun runtimeStatus(): McpToolGatewayResult {
        val status = runtime.status()
        return McpToolGatewayResult.Completed(
            JSONObject()
                .put("state", status.state.wireValue)
                .put("queued", status.queuedTaskCount)
                .put("active", status.hasActiveTask)
                .put(
                    "runtimeProbe",
                    JSONObject()
                        .put("status", status.runtimeProbe.status.name)
                        .put("rejectionReasons", org.json.JSONArray(status.runtimeProbe.rejectionReasons)),
                )
                .apply { status.runtimeId?.let { put("runtimeId", it) } },
        )
    }

    private fun unloadRuntime(arguments: JSONObject): McpToolGatewayResult {
        val runtimeId = arguments.optString("runtimeId")
        return when (runtime.unload(runtimeId)) {
            McpRuntimeUnloadResult.REQUESTED -> McpToolGatewayResult.Completed(
                JSONObject().put("runtimeId", runtimeId).put("unloadRequested", true),
            )

            McpRuntimeUnloadResult.NOT_LOADED -> McpToolGatewayResult.Rejected("RUNTIME_NOT_LOADED")

            McpRuntimeUnloadResult.BUSY -> McpToolGatewayResult.Rejected("RUNTIME_BUSY")
        }
    }

    private fun revokeClient(arguments: JSONObject): McpToolGatewayResult {
        val clientId = arguments.optString("clientId")
        if (!clients.revoke(clientId)) return McpToolGatewayResult.Rejected("CLIENT_NOT_FOUND")
        return McpToolGatewayResult.Completed(JSONObject().put("clientId", clientId).put("revoked", true))
    }

    /** A rotated bearer is only revealable by the unlocked local UI, never over MCP. */
    private fun rotateClientToken(arguments: JSONObject): McpToolGatewayResult {
        val clientId = arguments.optString("clientId")
        if (!clients.rotate(clientId)) return McpToolGatewayResult.Rejected("CLIENT_NOT_FOUND")
        return McpToolGatewayResult.Completed(
            JSONObject()
                .put("clientId", clientId)
                .put("rotated", true)
                .put("configurationAvailableOnDevice", true),
        )
    }

    companion object {
        private val SUPPORTED_TOOLS = setOf(
            "models.list",
            "models.get",
            "generation.create",
            "jobs.get",
            "jobs.list",
            "jobs.cancel",
            "presets.list",
            "presets.get",
            "presets.create",
            "presets.update",
            "presets.export",
            "presets.import",
            "presets.delete",
            "prompts.list",
            "prompts.get",
            "prompts.create",
            "prompts.update",
            "prompts.delete",
            "assets.list",
            "assets.delete",
            "downloads.list",
            "downloads.create",
            "downloads.cancel",
            "runtime.status",
            "runtime.unload",
            "client.revoke",
            "token.rotate",
        )

        /**
         * New local credentials intentionally use a reviewed, version-stable
         * scope template. Do not derive this from [SUPPORTED_TOOLS] or the
         * registry: registering a future tool must not silently expand the
         * authority granted to later credentials.
         */
        val DEFAULT_CLIENT_SCOPES: Set<String> = setOf(
            "models.read",
            "generation.run",
            "jobs.read",
            "jobs.write",
            "presets.read",
            "presets.write",
            "prompts.read",
            "prompts.write",
            "assets.read",
            "assets.write",
            "downloads.read",
            "downloads.write",
            "diagnostics.read",
            "diagnostics.write",
            "clients.write",
        )

        private const val HISTORY_ASSET_PREFIX = "history:"

        private val ASSET_TOOLS = setOf("assets.list", "assets.delete")

        private val DOWNLOAD_TOOLS = setOf("downloads.list", "downloads.create", "downloads.cancel")

        private val RUNTIME_TOOLS = setOf("runtime.status", "runtime.unload")

        private val CLIENT_TOOLS = setOf("client.revoke", "token.rotate")
    }

    private fun promptId(arguments: JSONObject): String? = arguments.optString("promptId").takeIf(String::isNotBlank)

    private fun promptJson(prompt: McpPrompt): JSONObject = JSONObject()
        .put("promptId", prompt.id)
        .put("title", prompt.title)
        .put("prompt", prompt.prompt)
        .put("negativePrompt", prompt.negativePrompt)
}

private fun JSONObject.optionalPositiveInt(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    val number = opt(key) as? Number ?: throw IllegalArgumentException("$key must be an integer")
    val value = number.toLong()
    require(number.toDouble() == value.toDouble() && value in 1..Int.MAX_VALUE) { "$key must be a positive integer" }
    return value.toInt()
}

private fun JSONObject.optionalLong(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    val number = opt(key) as? Number ?: throw IllegalArgumentException("$key must be an integer")
    val value = number.toLong()
    require(number.toDouble() == value.toDouble()) { "$key must be an integer" }
    return value
}

private fun JSONObject.optionalFiniteFloat(key: String): Float? {
    if (!has(key) || isNull(key)) return null
    val number = opt(key) as? Number ?: throw IllegalArgumentException("$key must be a number")
    return number.toFloat().also { require(it.isFinite()) { "$key must be finite" } }
}

data class McpPrompt(
    val id: String,
    val title: String,
    val prompt: String,
    val negativePrompt: String,
)

/** MCP-facing adapter for the existing prompt-template product domain. */
interface McpPromptStore {
    fun list(): List<McpPrompt>
    fun get(id: String): McpPrompt?
    fun create(title: String, prompt: String, negativePrompt: String): McpPrompt
    fun update(id: String, title: String, prompt: String, negativePrompt: String): McpPrompt?
    fun delete(id: String): Boolean

    object Unavailable : McpPromptStore {
        override fun list(): List<McpPrompt> = emptyList()
        override fun get(id: String): McpPrompt? = null
        override fun create(title: String, prompt: String, negativePrompt: String): McpPrompt = throw IllegalArgumentException("Prompt store is unavailable")
        override fun update(id: String, title: String, prompt: String, negativePrompt: String): McpPrompt? = null
        override fun delete(id: String): Boolean = false
    }
}

data class McpAsset(
    val id: String,
    val modelId: String,
    val mimeType: String,
    val timestamp: Long,
    val width: Int,
    val height: Int,
    val favorite: Boolean,
)

/** MCP-facing adapter for the product's generated-image history. */
interface McpAssetStore {
    fun list(): List<McpAsset>
    fun delete(assetId: String): Boolean

    object Unavailable : McpAssetStore {
        override fun list(): List<McpAsset> = emptyList()
        override fun delete(assetId: String): Boolean = false
    }
}

class AndroidMcpAssetStore(context: Context) : McpAssetStore {
    private val history = HistoryManager(context.applicationContext)

    override fun list(): List<McpAsset> = runBlocking {
        history.observeRecent(HistoryFilter(), MAX_LISTED_ASSETS).first().map { item ->
            McpAsset(
                id = "$HISTORY_ASSET_PREFIX${item.id}",
                modelId = item.modelId,
                mimeType = item.mimeType,
                timestamp = item.timestamp,
                width = item.params.width,
                height = item.params.height,
                favorite = item.favorite,
            )
        }
    }

    override fun delete(assetId: String): Boolean {
        val id = assetId.removePrefix(HISTORY_ASSET_PREFIX).toLongOrNull() ?: return false
        val item = runBlocking { history.getItems(listOf(id)).singleOrNull() } ?: return false
        return runBlocking { history.deleteHistoryItem(item) }
    }

    private companion object {
        const val HISTORY_ASSET_PREFIX = "history:"
        const val MAX_LISTED_ASSETS = 100
    }
}

class AndroidMcpPromptStore(context: Context) : McpPromptStore {
    private val repository = PromptRepository(context.applicationContext)

    override fun list(): List<McpPrompt> = runBlocking {
        repository.observeAll().first().map(::toMcpPrompt)
    }

    override fun get(id: String): McpPrompt? = id.toLongOrNull()?.let { promptId ->
        runBlocking { repository.get(promptId)?.let(::toMcpPrompt) }
    }

    override fun create(title: String, prompt: String, negativePrompt: String): McpPrompt = runBlocking {
        repository.create(title, prompt, negativePrompt).let(::toMcpPrompt)
    }

    override fun update(id: String, title: String, prompt: String, negativePrompt: String): McpPrompt? {
        val promptId = id.toLongOrNull() ?: return null
        return runBlocking { repository.update(promptId, title, prompt, negativePrompt)?.let(::toMcpPrompt) }
    }

    override fun delete(id: String): Boolean = id.toLongOrNull()?.let { promptId ->
        runBlocking { repository.delete(promptId) }
    } ?: false

    private fun toMcpPrompt(prompt: io.github.xororz.localdream.data.db.PromptTemplateEntity) = McpPrompt(
        id = prompt.id.toString(),
        title = prompt.title,
        prompt = prompt.prompt,
        negativePrompt = prompt.negativePrompt,
    )
}

data class McpJobRecord(
    val id: String,
    val ownerId: String,
    val presetId: String,
    val presetRevision: Long,
    val presetConfigJson: String = "{}",
    val status: InferenceJobStatus,
)

data class McpHistoryAsset(val assetId: String, val mimeType: String)

interface McpJobStore {
    fun accept(ownerId: String, modelId: String, explicitPresetId: String?): McpJobRecord
    fun get(jobId: String): McpJobRecord?
    fun listFor(ownerId: String): List<McpJobRecord>
    fun updateStatus(jobId: String, status: InferenceJobStatus)
    fun discard(jobId: String)
    fun historyAssetFor(jobId: String): McpHistoryAsset?
}

class RoomMcpJobStore(private val database: AppDatabase) : McpJobStore {
    private val repository = RoomInferenceJobRepository(database)

    override fun accept(ownerId: String, modelId: String, explicitPresetId: String?): McpJobRecord = runBlocking {
        repository.accept(ownerId, modelId = modelId, explicitPresetId = explicitPresetId).let { snapshot ->
            McpJobRecord(snapshot.jobId, ownerId, snapshot.presetId, snapshot.revision, snapshot.configJson, InferenceJobStatus.QUEUED)
        }
    }

    override fun get(jobId: String): McpJobRecord? = runBlocking {
        val job = database.inferenceJobDao().getById(jobId) ?: return@runBlocking null
        val snapshot = database.inferenceJobDao().snapshotFor(jobId) ?: return@runBlocking null
        McpJobRecord(job.id, job.ownerId, snapshot.presetId, snapshot.revision, snapshot.configJson, InferenceJobStatus.fromWire(job.status))
    }

    override fun listFor(ownerId: String): List<McpJobRecord> = runBlocking {
        database.inferenceJobDao().listForOwner(ownerId, MAX_LISTED_JOBS).mapNotNull { job ->
            val snapshot = database.inferenceJobDao().snapshotFor(job.id) ?: return@mapNotNull null
            McpJobRecord(job.id, job.ownerId, snapshot.presetId, snapshot.revision, snapshot.configJson, InferenceJobStatus.fromWire(job.status))
        }
    }

    override fun updateStatus(jobId: String, status: InferenceJobStatus) {
        runBlocking { repository.updateStatus(jobId, status) }
    }

    override fun discard(jobId: String) {
        runBlocking { repository.discard(jobId) }
    }

    override fun historyAssetFor(jobId: String): McpHistoryAsset? = runBlocking {
        database.historyDao().getByJobId(jobId)?.let { McpHistoryAsset("history:${it.id}", it.mimeType) }
    }

    private companion object {
        const val MAX_LISTED_JOBS = 100
    }
}

data class McpGenerationRequest(
    val job: McpJobRecord,
    val parameters: ImageRequestParameters,
) {
    val modelId: String get() = requireNotNull(parameters.modelId)
    val prompt: String get() = parameters.prompt
    val negativePrompt: String get() = parameters.negativePrompt
    val seed: Long? get() = parameters.seed
    val width: Int? get() = parameters.width
    val height: Int? get() = parameters.height
    val scheduler: String get() = parameters.scheduler
    val steps: Int get() = parameters.steps
    val cfg: Float get() = parameters.cfg
    val denoiseStrength: Float get() = parameters.denoiseStrength
}

enum class McpGenerationScheduleResult(val code: String) {
    ACCEPTED("OK"),
    QUEUE_FULL("QUEUE_FULL"),
    PIPELINE_BUSY("PIPELINE_BUSY"),
}

fun interface McpGenerationScheduler {
    fun submit(request: McpGenerationRequest): McpGenerationScheduleResult
}

/** Cancels a single MCP Job without affecting other jobs of the same client. */
fun interface McpGenerationCanceller {
    fun cancel(jobId: String): Boolean

    object Unavailable : McpGenerationCanceller {
        override fun cancel(jobId: String) = false
    }
}

/** Runs real native generation under the shared dispatcher, then saves a MCP-owned history row. */
class AndroidMcpGenerationScheduler(
    private val context: Context,
    private val dispatcher: InferenceDispatcher,
    private val jobs: McpJobStore,
) : McpGenerationScheduler,
    McpGenerationCanceller {
    private val catalog = InstalledModelCatalog(context)
    private val coordinator = BackendRuntimeCoordinator(context)
    private val backend = NativeBackendClient()
    private val history = HistoryManager(context)
    private val cancelledJobs = ConcurrentHashMap.newKeySet<String>()
    private val executionFinished = ConcurrentHashMap<String, CompletableFuture<Unit>>()
    private val savedAssets = ConcurrentHashMap<String, HistoryItem>()
    private val schedulerMonitor = Any()
    private var stopping = false

    @Volatile private var activeJobId: String? = null

    override fun submit(request: McpGenerationRequest): McpGenerationScheduleResult {
        val submission = synchronized(schedulerMonitor) {
            if (stopping) return McpGenerationScheduleResult.PIPELINE_BUSY
            dispatcher.submit(request.job.id, request.modelId) {
                activeJobId = request.job.id
                jobs.updateStatus(request.job.id, InferenceJobStatus.RUNNING)
                McpTaskEventBus.publish(McpTaskEventBus.Event(request.job.ownerId, request.job.id, InferenceJobStatus.RUNNING))
                try {
                    val entry = runBlocking { catalog.find(request.modelId) }
                        ?.takeIf { it.kind == InstalledModelCatalog.Kind.GENERATION }
                        ?: throw IllegalArgumentException("Requested model is not installed")
                    val presetConfig = PerformancePresetConfig.parse(request.job.presetConfigJson)
                    val presetEngineConfig = presetConfig.requireExecutableSnapshot(
                        request.job.presetId == PerformancePresetRepository.COMPATIBILITY_FALLBACK_ID,
                    )
                    val dimensions = runBlocking {
                        coordinator.ensureReady(entry, request.width, request.height, presetEngineConfig)
                    }
                    val negativePrompt = request.negativePrompt.ifBlank {
                        runBlocking { GenerationPreferences(context).getGlobalNegativePrompt() }
                    }
                    val generated = backend.generate(
                        request.parameters.copy(modelId = entry.id, negativePrompt = negativePrompt),
                        dimensions.first,
                        dimensions.second,
                        onDiffusionStep = { step, totalSteps ->
                            if (request.job.id !in cancelledJobs) {
                                McpTaskEventBus.publish(
                                    McpTaskEventBus.Event(
                                        clientId = request.job.ownerId,
                                        jobId = request.job.id,
                                        status = InferenceJobStatus.RUNNING,
                                        diffusionStep = step,
                                        totalDiffusionSteps = totalSteps,
                                    ),
                                )
                            }
                        },
                    )
                    // MCP is an independent native inference entrypoint. Its
                    // completed image is equally valid attestation evidence.
                    NativeRuntimeAttestationRecorder.record(context, entry.id)
                    // Cancellation may race native completion. Never materialize a
                    // historical asset after the Job has been cancelled.
                    if (request.job.id in cancelledJobs || jobs.get(request.job.id)?.status == InferenceJobStatus.CANCELLED) {
                        return@submit Unit
                    }
                    val saved = runBlocking {
                        history.saveEncodedImage(
                            modelId = entry.id,
                            encodedImage = generated.bytes,
                            mimeType = generated.mimeType,
                            params = GenerationParameters(
                                steps = request.steps,
                                cfg = request.cfg,
                                seed = generated.seed,
                                prompt = request.prompt,
                                negativePrompt = negativePrompt,
                                generationTime = null,
                                width = dimensions.first,
                                height = dimensions.second,
                                runOnCpu = entry.model?.runOnCpu == true,
                                denoiseStrength = request.denoiseStrength,
                                useOpenCL = false,
                                scheduler = request.scheduler,
                                mode = GenerationMode.TXT2IMG,
                            ),
                            mode = GenerationMode.TXT2IMG,
                            origin = AssetOrigin.MCP,
                            inferenceAssociation = InferenceHistoryAssociation(
                                request.job.id,
                                request.job.presetId,
                                request.job.presetRevision,
                            ),
                        )
                    }
                    checkNotNull(saved) { "Generated image could not be saved" }
                    savedAssets[request.job.id] = saved
                    if (request.job.id in cancelledJobs || jobs.get(request.job.id)?.status == InferenceJobStatus.CANCELLED) {
                        discardSavedAsset(request.job.id)
                        return@submit Unit
                    }
                    if (jobs.get(request.job.id)?.status != InferenceJobStatus.CANCELLED) {
                        jobs.updateStatus(request.job.id, InferenceJobStatus.SUCCEEDED)
                        McpTaskEventBus.publish(McpTaskEventBus.Event(request.job.ownerId, request.job.id, InferenceJobStatus.SUCCEEDED))
                        savedAssets.remove(request.job.id)
                    }
                } catch (error: Throwable) {
                    if (jobs.get(request.job.id)?.status != InferenceJobStatus.CANCELLED) {
                        jobs.updateStatus(request.job.id, InferenceJobStatus.FAILED)
                        McpTaskEventBus.publish(McpTaskEventBus.Event(request.job.ownerId, request.job.id, InferenceJobStatus.FAILED))
                    }
                    throw error
                } finally {
                    activeJobId = null
                }
            }.also { admitted ->
                if (admitted is BoundedSerialExecutor.Submission.Accepted) {
                    executionFinished[request.job.id] = admitted.executionFinished
                    admitted.executionFinished.whenComplete { _, _ -> executionFinished.remove(request.job.id) }
                }
            }
        }
        return when (submission) {
            null -> McpGenerationScheduleResult.PIPELINE_BUSY
            is BoundedSerialExecutor.Submission.Accepted -> McpGenerationScheduleResult.ACCEPTED
            is BoundedSerialExecutor.Submission.Rejected -> McpGenerationScheduleResult.QUEUE_FULL
        }
    }

    override fun cancel(jobId: String): Boolean {
        if (jobs.get(jobId)?.status !in setOf(InferenceJobStatus.QUEUED, InferenceJobStatus.RUNNING)) return false
        cancelledJobs += jobId
        dispatcher.cancelOwner(jobId)
        if (activeJobId == jobId) backend.cancelAll()
        discardSavedAsset(jobId)
        return true
    }

    /** Service teardown is scoped to MCP-owned Jobs and waits on native unwind. */
    fun cancelAll(): List<CompletableFuture<Unit>> {
        val jobIds = synchronized(schedulerMonitor) {
            stopping = true
            executionFinished.keys.toList()
        }
        jobIds.forEach { jobId ->
            if (cancel(jobId)) {
                jobs.updateStatus(jobId, InferenceJobStatus.CANCELLED)
                jobs.get(jobId)?.let { job ->
                    McpTaskEventBus.publish(McpTaskEventBus.Event(job.ownerId, job.id, InferenceJobStatus.CANCELLED))
                }
            }
        }
        return jobIds.mapNotNull(executionFinished::get)
    }

    private fun discardSavedAsset(jobId: String) {
        savedAssets.remove(jobId)?.let { saved -> runBlocking { history.deleteHistoryItem(saved) } }
    }
}

/** Reads only the history row selected by a validated public asset id. */
class McpHistoryImageContentResolver(
    private val context: Context,
    private val history: HistoryDao,
) : McpImageContentResolver {
    override fun resolve(assetId: String): McpImageContent? = runBlocking {
        if (!assetId.startsWith("history:")) return@runBlocking null
        val historyId = assetId.removePrefix("history:").toLongOrNull() ?: return@runBlocking null
        val entity = history.getById(historyId) ?: return@runBlocking null
        val root = File(context.filesDir, "history").canonicalFile
        val image = File(context.filesDir, entity.imagePath).canonicalFile
        if (
            !entity.imagePath.startsWith("history/") || !image.path.startsWith("${root.path}/") ||
            !image.isFile || image.length() !in 1..MAX_IMAGE_BYTES
        ) {
            return@runBlocking null
        }
        McpImageContent(image.readBytes(), entity.mimeType)
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 32L * 1024L * 1024L
    }
}
