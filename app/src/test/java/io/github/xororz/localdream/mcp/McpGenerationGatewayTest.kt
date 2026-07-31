package io.github.xororz.localdream.mcp

import io.github.xororz.localdream.data.InferenceJobStatus
import io.github.xororz.localdream.data.PerformancePreset
import io.github.xororz.localdream.data.PerformancePresetBinding
import io.github.xororz.localdream.data.PerformancePresetRepository
import io.github.xororz.localdream.data.PresetDeleteResult
import io.github.xororz.localdream.data.RuntimeProbeProjection
import io.github.xororz.localdream.data.RuntimeProbeStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpGenerationGatewayTest {
    @Test
    fun capabilityDiscoveryExposesOnlyToolsWithConcreteDomainHandlersAndUsesStableDefaultScopeTemplate() {
        val gateway = McpGenerationGateway(FakeJobs(), RecordingScheduler())

        assertEquals(
            setOf(
                "models.list", "models.get", "generation.create", "jobs.get", "jobs.list", "jobs.cancel",
                "presets.list", "presets.get", "presets.create", "presets.update", "presets.export", "presets.import", "presets.delete",
                "prompts.list", "prompts.get", "prompts.create", "prompts.update", "prompts.delete",
            ),
            McpToolRegistry.definitions.values.filter(gateway::supports).map(McpToolDefinition::name).toSet(),
        )
        assertTrue(!gateway.supports(requireNotNull(McpToolRegistry.definitions["assets.delete"])))
        assertTrue(!gateway.supports(requireNotNull(McpToolRegistry.definitions["downloads.create"])))
        assertTrue(!gateway.supports(requireNotNull(McpToolRegistry.definitions["presets.reorder"])))
        assertEquals(
            setOf(
                "models.read", "generation.run", "jobs.read", "jobs.write",
                "presets.read", "presets.write", "prompts.read", "prompts.write",
                "assets.read", "assets.write", "downloads.read", "downloads.write",
                "diagnostics.read", "diagnostics.write", "clients.write",
            ),
            McpGenerationGateway.DEFAULT_CLIENT_SCOPES,
        )
    }

    @Test
    fun generationCreatesDurableJobAndCompletedJobExposesStableAssetLink() {
        val jobs = FakeJobs()
        val scheduler = RecordingScheduler()
        val gateway = McpGenerationGateway(jobs, scheduler)
        val client = McpAuthenticatedClient("client-a", 1, emptySet(), McpTransport.LOOPBACK)
        val create = gateway.execute(
            client,
            invocation("generation.create"),
            JSONObject()
                .put("modelId", "model-a")
                .put("prompt", "a safe prompt"),
        ) as McpToolGatewayResult.Completed

        assertEquals("job-1", create.jobId)
        assertEquals("job-1", create.result.getString("jobId"))
        assertEquals("working", create.result.getString("task"))
        assertEquals("model-a", scheduler.request?.modelId)

        jobs.status = InferenceJobStatus.SUCCEEDED
        val completed = gateway.execute(client, invocation("jobs.get"), JSONObject().put("jobId", "job-1"))
            as McpToolGatewayResult.Completed
        val path = completed.result.getString("image")
        assertEquals("completed", completed.result.getString("task"))
        assertEquals("/assets/asset-1", path)
        val resourceLink = completed.result.getJSONArray("content").getJSONObject(0)
        assertEquals("resource_link", resourceLink.getString("type"))
        assertEquals(path, resourceLink.getString("uri"))
        assertEquals("image/png", resourceLink.getString("mimeType"))
    }

    @Test
    fun generationProjectsW7FixtureParametersIntoTheAcceptedRequest() {
        val scheduler = RecordingScheduler()
        val gateway = McpGenerationGateway(FakeJobs(), scheduler)
        val client = McpAuthenticatedClient("client-a", 1, emptySet(), McpTransport.LOOPBACK)

        val result = gateway.execute(
            client,
            invocation("generation.create"),
            JSONObject()
                .put("modelId", "model-a")
                .put("prompt", "fixed fixture")
                .put("negativePrompt", "fixed negative")
                .put("seed", 42L)
                .put("width", 1024)
                .put("height", 1024)
                .put("scheduler", "euler_a")
                .put("steps", 20)
                .put("cfg", 7.0)
                .put("denoiseStrength", 0.65),
        ) as McpToolGatewayResult.Completed

        assertEquals("job-1", result.jobId)
        val request = requireNotNull(scheduler.request)
        assertEquals(42L, request.seed)
        assertEquals(1024, request.width)
        assertEquals(1024, request.height)
        assertEquals("euler_a", request.scheduler)
        assertEquals(20, request.steps)
        assertEquals(7f, request.cfg)
        assertEquals(0.65f, request.denoiseStrength)
    }

    @Test
    fun generationRejectsMalformedFixtureParametersBeforeScheduling() {
        val scheduler = RecordingScheduler()
        val gateway = McpGenerationGateway(FakeJobs(), scheduler)
        val client = McpAuthenticatedClient("client-a", 1, emptySet(), McpTransport.LOOPBACK)

        val result = gateway.execute(
            client,
            invocation("generation.create"),
            JSONObject().put("modelId", "model-a").put("prompt", "p").put("steps", "twenty"),
        ) as McpToolGatewayResult.Rejected

        assertEquals("INVALID_PARAMS", result.code)
        assertEquals(null, scheduler.request)
    }

    @Test
    fun jobsNeverExposeOtherClientsAndRejectedSchedulingLeavesNoJob() {
        val jobs = FakeJobs()
        val scheduler = RecordingScheduler(McpGenerationScheduleResult.QUEUE_FULL)
        val gateway = McpGenerationGateway(jobs, scheduler)
        val owner = McpAuthenticatedClient("client-a", 1, emptySet(), McpTransport.LOOPBACK)
        val other = McpAuthenticatedClient("client-b", 1, emptySet(), McpTransport.LOOPBACK)

        assertEquals(
            "QUEUE_FULL",
            (
                gateway.execute(owner, invocation("generation.create"), JSONObject().put("modelId", "model-a").put("prompt", "p"))
                    as McpToolGatewayResult.Rejected
                ).code,
        )
        assertTrue(jobs.discarded)
        assertEquals(
            "JOB_NOT_FOUND",
            (
                gateway.execute(other, invocation("jobs.get"), JSONObject().put("jobId", "job-1"))
                    as McpToolGatewayResult.Rejected
                ).code,
        )
    }

    @Test
    fun cancellingOwnActiveJobDelegatesOnlyThatJobAndKeepsItCancelled() {
        val jobs = FakeJobs().apply { status = InferenceJobStatus.RUNNING }
        val cancellations = RecordingCanceller()
        val gateway = McpGenerationGateway(
            jobs = jobs,
            scheduler = RecordingScheduler(),
            cancellations = cancellations,
        )
        val owner = McpAuthenticatedClient("client-a", 1, emptySet(), McpTransport.LOOPBACK)
        val other = McpAuthenticatedClient("client-b", 1, emptySet(), McpTransport.LOOPBACK)

        val cancelled = gateway.execute(owner, invocation("jobs.cancel"), JSONObject().put("jobId", "job-1"))
            as McpToolGatewayResult.Completed

        assertEquals("cancelled", cancelled.result.getString("task"))
        assertEquals(listOf("job-1"), cancellations.ids)
        assertEquals(InferenceJobStatus.CANCELLED, jobs.status)
        assertEquals(
            "JOB_NOT_FOUND",
            (gateway.execute(other, invocation("jobs.cancel"), JSONObject().put("jobId", "job-1")) as McpToolGatewayResult.Rejected).code,
        )
        val repeated = gateway.execute(owner, invocation("jobs.cancel"), JSONObject().put("jobId", "job-1"))
            as McpToolGatewayResult.Completed
        assertEquals("cancelled", repeated.result.getString("task"))
        assertEquals(listOf("job-1"), cancellations.ids)
    }

    @Test
    fun modelAndJobReadToolsExposeOnlyServerSelectedMetadataAndOwnedJobs() {
        val jobs = FakeJobs()
        val gateway = McpGenerationGateway(
            jobs = jobs,
            scheduler = RecordingScheduler(),
            models = FakeModels,
        )
        val client = McpAuthenticatedClient("client-a", 1, emptySet(), McpTransport.LOOPBACK)

        val listedModels = gateway.execute(client, invocation("models.list"), JSONObject())
            as McpToolGatewayResult.Completed
        val listed = listedModels.result.getJSONArray("models")
        assertEquals(1, listed.length())
        assertEquals("model-a", listed.getJSONObject(0).getString("id"))
        assertTrue(!listed.getJSONObject(0).has("path"))

        val model = gateway.execute(client, invocation("models.get"), JSONObject().put("modelId", "model-a"))
            as McpToolGatewayResult.Completed
        assertTrue(model.result.getBoolean("supportsImageInput"))
        assertEquals(
            "MODEL_NOT_FOUND",
            (
                gateway.execute(client, invocation("models.get"), JSONObject().put("modelId", "missing"))
                    as McpToolGatewayResult.Rejected
                ).code,
        )

        val listedJobs = gateway.execute(client, invocation("jobs.list"), JSONObject())
            as McpToolGatewayResult.Completed
        assertEquals("job-1", listedJobs.result.getJSONArray("jobs").getJSONObject(0).getString("jobId"))
        assertEquals("client-a", jobs.listedFor)
    }

    @Test
    fun promptToolsDelegateToTheProductPromptStoreAndSupportPartialUpdates() {
        val prompts = FakePrompts()
        val gateway = McpGenerationGateway(
            jobs = FakeJobs(),
            scheduler = RecordingScheduler(),
            prompts = prompts,
        )
        val client = McpAuthenticatedClient("client-a", 1, emptySet(), McpTransport.LOOPBACK)

        val created = gateway.execute(
            client,
            invocation("prompts.create"),
            JSONObject().put("title", "Portrait").put("prompt", "portrait, studio light"),
        ) as McpToolGatewayResult.Completed
        val promptId = created.result.getString("promptId")
        assertEquals("Portrait", created.result.getString("title"))

        val updated = gateway.execute(
            client,
            invocation("prompts.update"),
            JSONObject().put("promptId", promptId).put("negativePrompt", "blurry"),
        ) as McpToolGatewayResult.Completed
        assertEquals("portrait, studio light", updated.result.getString("prompt"))
        assertEquals("blurry", updated.result.getString("negativePrompt"))

        val listed = gateway.execute(client, invocation("prompts.list"), JSONObject()) as McpToolGatewayResult.Completed
        assertEquals(1, listed.result.getJSONArray("prompts").length())
        assertEquals(
            promptId,
            (gateway.execute(client, invocation("prompts.get"), JSONObject().put("promptId", promptId)) as McpToolGatewayResult.Completed)
                .result.getString("promptId"),
        )
        assertEquals(
            "PROMPT_NOT_FOUND",
            (gateway.execute(client, invocation("prompts.get"), JSONObject().put("promptId", "missing")) as McpToolGatewayResult.Rejected).code,
        )
        assertEquals(
            true,
            (gateway.execute(client, invocation("prompts.delete"), JSONObject().put("promptId", promptId)) as McpToolGatewayResult.Completed)
                .result.getBoolean("deleted"),
        )
    }

    @Test
    fun assetToolsProjectProductHistoryWithoutPathsAndDeleteOnlyTheSelectedAsset() {
        val assets = FakeAssets()
        val gateway = McpGenerationGateway(
            jobs = FakeJobs(),
            scheduler = RecordingScheduler(),
            assets = assets,
        )
        val client = McpAuthenticatedClient("client-a", 1, emptySet(), McpTransport.LOOPBACK)

        assertTrue(gateway.supports(requireNotNull(McpToolRegistry.definitions["assets.list"])))
        assertTrue(gateway.supports(requireNotNull(McpToolRegistry.definitions["assets.delete"])))
        val listed = gateway.execute(client, invocation("assets.list"), JSONObject()) as McpToolGatewayResult.Completed
        val asset = listed.result.getJSONArray("assets").getJSONObject(0)
        assertEquals("history:7", asset.getString("assetId"))
        assertEquals("image/png", asset.getString("mimeType"))
        assertTrue(!asset.has("path"))
        assertTrue(!asset.has("prompt"))

        val deleted = gateway.execute(
            client,
            invocation("assets.delete"),
            JSONObject().put("assetId", "history:7"),
        ) as McpToolGatewayResult.Completed
        assertEquals(true, deleted.result.getBoolean("deleted"))
        assertEquals(listOf("history:7"), assets.deleted)
        assertEquals(
            "ASSET_NOT_FOUND",
            (
                gateway.execute(client, invocation("assets.delete"), JSONObject().put("assetId", "history:missing"))
                    as McpToolGatewayResult.Rejected
                ).code,
        )
    }

    @Test
    fun downloadToolsUseOnlyProductModelIdsAndProjectNoTransportDetails() {
        val downloads = FakeDownloads()
        val gateway = McpGenerationGateway(
            jobs = FakeJobs(),
            scheduler = RecordingScheduler(),
            downloads = downloads,
        )
        val client = McpAuthenticatedClient("client-a", 1, emptySet(), McpTransport.LOOPBACK)

        assertTrue(gateway.supports(requireNotNull(McpToolRegistry.definitions["downloads.list"])))
        val listed = gateway.execute(client, invocation("downloads.list"), JSONObject()) as McpToolGatewayResult.Completed
        val download = listed.result.getJSONArray("downloads").getJSONObject(0)
        assertEquals("model-a", download.getString("modelId"))
        assertEquals("downloading", download.getString("status"))
        assertTrue(!download.has("url"))
        assertTrue(!download.has("path"))

        val created = gateway.execute(
            client,
            invocation("downloads.create"),
            JSONObject().put("modelId", "model-a"),
        ) as McpToolGatewayResult.Completed
        assertEquals("model-a", created.result.getString("downloadId"))
        assertEquals(listOf("model-a"), downloads.created)

        val cancelled = gateway.execute(
            client,
            invocation("downloads.cancel"),
            JSONObject().put("downloadId", "model-a"),
        ) as McpToolGatewayResult.Completed
        assertTrue(cancelled.result.getBoolean("cancelRequested"))
        assertEquals(listOf("model-a"), downloads.cancelled)
    }

    @Test
    fun runtimeAndClientToolsUseProductAdaptersWithoutExposingCredentials() {
        val runtime = FakeRuntime()
        val clients = FakeClients()
        val gateway = McpGenerationGateway(
            jobs = FakeJobs(),
            scheduler = RecordingScheduler(),
            runtime = runtime,
            clients = clients,
        )
        val client = McpAuthenticatedClient("admin-client", 1, emptySet(), McpTransport.LOOPBACK)

        assertTrue(gateway.supports(requireNotNull(McpToolRegistry.definitions["runtime.status"])))
        assertTrue(gateway.supports(requireNotNull(McpToolRegistry.definitions["runtime.unload"])))
        assertTrue(gateway.supports(requireNotNull(McpToolRegistry.definitions["client.revoke"])))
        assertTrue(gateway.supports(requireNotNull(McpToolRegistry.definitions["token.rotate"])))

        val status = gateway.execute(client, invocation("runtime.status"), JSONObject()) as McpToolGatewayResult.Completed
        assertEquals("running", status.result.getString("state"))
        assertEquals("model-a", status.result.getString("runtimeId"))
        assertEquals(1, status.result.getInt("queued"))
        assertTrue(!status.result.has("path"))
        assertEquals("REJECTED", status.result.getJSONObject("runtimeProbe").getString("status"))
        assertEquals(
            "DEVICE_MODEL_MISMATCH",
            status.result.getJSONObject("runtimeProbe").getJSONArray("rejectionReasons").getString(0),
        )

        val unloaded = gateway.execute(
            client,
            invocation("runtime.unload"),
            JSONObject().put("runtimeId", "model-a"),
        ) as McpToolGatewayResult.Completed
        assertTrue(unloaded.result.getBoolean("unloadRequested"))
        assertEquals(listOf("model-a"), runtime.unloaded)

        val revoked = gateway.execute(
            client,
            invocation("client.revoke"),
            JSONObject().put("clientId", "old-client"),
        ) as McpToolGatewayResult.Completed
        assertTrue(revoked.result.getBoolean("revoked"))
        assertEquals(listOf("old-client"), clients.revoked)

        val rotated = gateway.execute(
            client,
            invocation("token.rotate"),
            JSONObject().put("clientId", "old-client"),
        ) as McpToolGatewayResult.Completed
        assertTrue(rotated.result.getBoolean("rotated"))
        assertTrue(rotated.result.getBoolean("configurationAvailableOnDevice"))
        assertTrue(!rotated.result.has("token"))
        assertEquals(listOf("old-client"), clients.rotated)
    }

    @Test
    fun presetToolsDelegateToTheProductPresetStoreAndPreserveRevisionConflict() {
        val presets = FakePresets()
        val gateway = McpGenerationGateway(
            jobs = FakeJobs(),
            scheduler = RecordingScheduler(),
            presets = presets,
        )
        val client = McpAuthenticatedClient("client-a", 1, emptySet(), McpTransport.LOOPBACK)

        val created = gateway.execute(
            client,
            invocation("presets.create"),
            JSONObject().put("name", "Fast").put("selector", "model-a").put("configJson", "{}"),
        ) as McpToolGatewayResult.Completed
        val id = created.result.getString("presetId")
        assertEquals("USER", created.result.getString("kind"))

        val conflict = gateway.execute(
            client,
            invocation("presets.update"),
            JSONObject().put("presetId", id).put("revision", 0).put("name", "Fast 2"),
        ) as McpToolGatewayResult.Rejected
        assertEquals("PRESET_REVISION_CONFLICT", conflict.code)

        val updated = gateway.execute(
            client,
            invocation("presets.update"),
            JSONObject().put("presetId", id).put("revision", 1).put("name", "Fast 2"),
        ) as McpToolGatewayResult.Completed
        assertEquals(2, updated.result.getLong("revision"))

        val exported = gateway.execute(client, invocation("presets.export"), JSONObject()) as McpToolGatewayResult.Completed
        assertEquals("vision-dream-performance-preset", JSONObject(exported.result.getString("envelope")).getString("format"))
        val imported = gateway.execute(
            client,
            invocation("presets.import"),
            JSONObject().put(
                "envelope",
                JSONObject(exported.result.getString("envelope"))
                    .put(
                        "presets",
                        org.json.JSONArray().put(
                            JSONObject().put("name", "Imported").put("selector", "model-a").put("configJson", "{}"),
                        ),
                    )
                    .toString(),
            ),
        ) as McpToolGatewayResult.Completed
        assertEquals("Imported", imported.result.getJSONArray("presets").getJSONObject(0).getString("name"))

        presets.reboundBindingKeys = listOf("DEFAULT", "MODEL:model-a")
        val deleted = gateway.execute(client, invocation("presets.delete"), JSONObject().put("presetId", id))
            as McpToolGatewayResult.Completed
        assertTrue(deleted.result.getBoolean("deleted"))
        assertEquals(
            listOf("DEFAULT", "MODEL:model-a"),
            (0 until deleted.result.getJSONArray("reboundBindingKeys").length()).map {
                deleted.result.getJSONArray("reboundBindingKeys").getString(it)
            },
        )
    }

    private fun invocation(name: String): McpToolInvocation = McpToolInvocation(
        definition = requireNotNull(McpToolRegistry.definitions[name]),
        targetIds = emptySet(),
        parameterDigest = "digest",
    )

    private class RecordingScheduler(
        private val result: McpGenerationScheduleResult = McpGenerationScheduleResult.ACCEPTED,
    ) : McpGenerationScheduler {
        var request: McpGenerationRequest? = null
        override fun submit(request: McpGenerationRequest): McpGenerationScheduleResult {
            this.request = request
            return result
        }
    }

    private class RecordingCanceller : McpGenerationCanceller {
        val ids = mutableListOf<String>()

        override fun cancel(jobId: String): Boolean {
            ids += jobId
            return true
        }
    }

    private class FakeJobs : McpJobStore {
        var status = InferenceJobStatus.QUEUED
        var discarded = false
        var listedFor: String? = null

        override fun accept(ownerId: String, modelId: String, explicitPresetId: String?) = McpJobRecord(
            id = "job-1",
            ownerId = ownerId,
            presetId = explicitPresetId ?: PerformancePresetRepository.COMPATIBILITY_FALLBACK_ID,
            presetRevision = 1,
            status = status,
        )

        override fun get(jobId: String): McpJobRecord? = McpJobRecord(
            id = jobId,
            ownerId = "client-a",
            presetId = "preset-1",
            presetRevision = 1,
            status = status,
        )

        override fun listFor(ownerId: String): List<McpJobRecord> {
            listedFor = ownerId
            return listOf(get("job-1")!!)
        }

        override fun updateStatus(jobId: String, status: InferenceJobStatus) {
            this.status = status
        }

        override fun discard(jobId: String) {
            discarded = true
        }

        override fun historyAssetFor(jobId: String) = McpHistoryAsset("asset-1", "image/png")
    }

    private object FakeModels : McpInstalledModelCatalog {
        override fun all(): List<McpInstalledModel> = listOf(
            McpInstalledModel("model-a", "Model A", "generation", "sd15npu", 512, true),
        )
    }

    private class FakePrompts : McpPromptStore {
        private val values = linkedMapOf<String, McpPrompt>()
        private var nextId = 1

        override fun list(): List<McpPrompt> = values.values.toList()

        override fun get(id: String): McpPrompt? = values[id]

        override fun create(title: String, prompt: String, negativePrompt: String): McpPrompt {
            val value = McpPrompt((nextId++).toString(), title, prompt, negativePrompt)
            values[value.id] = value
            return value
        }

        override fun update(id: String, title: String, prompt: String, negativePrompt: String): McpPrompt? {
            val existing = values[id] ?: return null
            return existing.copy(title = title, prompt = prompt, negativePrompt = negativePrompt).also { values[id] = it }
        }

        override fun delete(id: String): Boolean = values.remove(id) != null
    }

    private class FakeAssets : McpAssetStore {
        private val values = linkedMapOf(
            "history:7" to McpAsset("history:7", "model-a", "image/png", 7L, 512, 512, true),
        )
        val deleted = mutableListOf<String>()

        override fun list(): List<McpAsset> = values.values.toList()

        override fun delete(assetId: String): Boolean = values.remove(assetId)?.also { deleted += assetId } != null
    }

    private class FakeDownloads : McpDownloadStore {
        val created = mutableListOf<String>()
        val cancelled = mutableListOf<String>()

        override fun list(): List<McpDownload> = listOf(
            McpDownload("model-a", "Model A", "downloading", downloadedBytes = 10, totalBytes = 20),
        )

        override fun create(modelId: String): McpDownloadCreateResult {
            created += modelId
            return McpDownloadCreateResult.ACCEPTED
        }

        override fun cancel(downloadId: String): Boolean {
            cancelled += downloadId
            return true
        }
    }

    private class FakeRuntime : McpRuntimeStore {
        val unloaded = mutableListOf<String>()

        override fun status() = McpRuntimeStatus(
            state = McpRuntimeState.RUNNING,
            runtimeId = "model-a",
            queuedTaskCount = 1,
            hasActiveTask = true,
            runtimeProbe = RuntimeProbeProjection(
                status = RuntimeProbeStatus.REJECTED,
                rejectionReasons = listOf("DEVICE_MODEL_MISMATCH"),
            ),
        )

        override fun unload(runtimeId: String): McpRuntimeUnloadResult {
            unloaded += runtimeId
            return McpRuntimeUnloadResult.REQUESTED
        }
    }

    private class FakeClients : McpClientManagementStore {
        val revoked = mutableListOf<String>()
        val rotated = mutableListOf<String>()

        override fun revoke(clientId: String): Boolean {
            revoked += clientId
            return true
        }

        override fun rotate(clientId: String): Boolean {
            rotated += clientId
            return true
        }
    }

    private class FakePresets : McpPresetStore {
        private val values = linkedMapOf<String, PerformancePreset>()
        private val bindings = linkedMapOf<String, PerformancePresetBinding>()
        private var nextId = 1
        var reboundBindingKeys: List<String> = emptyList()

        override fun list(): List<PerformancePreset> = values.values.toList()

        override fun get(id: String): PerformancePreset? = values[id]

        override fun create(name: String, selector: String, configJson: String): PerformancePreset = PerformancePreset(
            id = "preset-${nextId++}",
            name = name,
            selector = selector,
            configJson = configJson,
            revision = 1,
        ).also { values[it.id] = it }

        override fun update(id: String, revision: Long, name: String, selector: String, configJson: String): PerformancePreset {
            val current = requireNotNull(values[id])
            require(current.revision == revision) { "Preset revision conflict" }
            return current.copy(name = name, selector = selector, configJson = configJson, revision = revision + 1)
                .also { values[id] = it }
        }

        override fun delete(id: String): PresetDeleteResult = PresetDeleteResult(
            deleted = values.remove(id) != null,
            reboundBindingKeys = reboundBindingKeys,
        )

        override fun binding(bindingKey: String): PerformancePresetBinding? = bindings[bindingKey]

        override fun bind(bindingKey: String, presetId: String): PerformancePresetBinding = PerformancePresetBinding(
            bindingKey = bindingKey,
            presetId = presetId,
        ).also { bindings[bindingKey] = it }

        override fun unbind(bindingKey: String): Boolean = bindings.remove(bindingKey) != null

        override fun exportEnvelope(): String = JSONObject()
            .put("format", "vision-dream-performance-preset")
            .put("schemaVersion", 1)
            .put("presets", org.json.JSONArray())
            .toString()

        override fun importEnvelope(envelope: String): List<PerformancePreset> {
            val objectValue = JSONObject(envelope).getJSONArray("presets").getJSONObject(0)
            return listOf(create(objectValue.getString("name"), objectValue.getString("selector"), objectValue.getString("configJson")))
        }
    }
}
