package io.github.xororz.localdream.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpAuthorizationTest {

    @Test
    fun replayStoreFailsClosedAtCapacityWithoutEvictingSafeTombstones() {
        val registry = McpToolRegistry()
        val client = McpAuthenticatedClient("client-a", 1, setOf("prompts.write"), McpTransport.LOOPBACK)
        val store = McpMutationReplayStore(maxEntries = 2)
        var executions = 0
        val original = mutation(registry, client, "original")

        store.execute(client, original, "original") {
            executions += 1
            McpToolGatewayResult.Completed(JSONObject().put("promptId", "original"))
        }
        repeat(1) { index ->
            val key = "filler-$index"
            store.execute(client, mutation(registry, client, key), key) {
                executions += 1
                McpToolGatewayResult.Completed(JSONObject().put("promptId", key))
            }
        }

        val rejected = store.execute(client, mutation(registry, client, "overflow"), "overflow") {
            executions += 1
            McpToolGatewayResult.Completed(JSONObject().put("promptId", "duplicate"))
        } as McpToolGatewayResult.Rejected
        assertEquals("IDEMPOTENCY_LEDGER_FULL", rejected.code)
        val replay = store.execute(client, original, "original") {
            executions += 1
            McpToolGatewayResult.Completed(JSONObject().put("promptId", "duplicate"))
        } as McpToolGatewayResult.Completed

        assertEquals("original", replay.result.getString("promptId"))
        assertEquals(2, executions)
    }

    @Test
    fun replayStorePrunesExpiredTombstonesBeforeReadmittingAndKeepsBoundAfterRecreation() {
        var now = 0L
        val registry = McpToolRegistry()
        val client = McpAuthenticatedClient("client-a", 1, setOf("prompts.write"), McpTransport.LOOPBACK)
        val persistence = InMemoryMutationReplayPersistence()
        val first = McpMutationReplayStore(persistence, { now }, maxEntries = 1)
        var executions = 0
        first.execute(client, mutation(registry, client, "old"), "old") {
            executions += 1
            McpToolGatewayResult.Completed(JSONObject().put("promptId", "old"))
        }
        assertEquals(
            "IDEMPOTENCY_LEDGER_FULL",
            (
                McpMutationReplayStore(persistence, { now }, maxEntries = 1)
                    .execute(client, mutation(registry, client, "blocked"), "blocked") {
                        executions += 1
                        McpToolGatewayResult.Completed(JSONObject())
                    } as McpToolGatewayResult.Rejected
                ).code,
        )
        now = 24 * 60 * 60 * 1000L + 1
        val admitted = McpMutationReplayStore(persistence, { now }, maxEntries = 1)
            .execute(client, mutation(registry, client, "new"), "new") {
                executions += 1
                McpToolGatewayResult.Completed(JSONObject().put("promptId", "new"))
            } as McpToolGatewayResult.Completed
        assertEquals("new", admitted.result.getString("promptId"))
        assertEquals(2, executions)
    }

    @Test
    fun replayStoreRejectsExpiredKeyRatherThanPerformingAnUnsafeRetry() {
        var now = 0L
        val registry = McpToolRegistry()
        val client = McpAuthenticatedClient("client-a", 1, setOf("prompts.write"), McpTransport.LOOPBACK)
        val store = McpMutationReplayStore(clock = { now })
        var executions = 0
        val invocation = mutation(registry, client, "expired")

        store.execute(client, invocation, "expired") {
            executions += 1
            McpToolGatewayResult.Completed(JSONObject().put("promptId", "original"))
        }
        now = 16 * 60 * 1000L

        assertEquals(
            "IDEMPOTENCY_RETRY_WINDOW_EXPIRED",
            (
                store.execute(client, invocation, "expired") {
                    executions += 1
                    McpToolGatewayResult.Completed(JSONObject().put("promptId", "duplicate"))
                } as McpToolGatewayResult.Rejected
                ).code,
        )
        assertEquals(1, executions)
    }

    @Test
    fun replayStoreRestoresTheClientTokenToolPartitionAfterListenerRecreation() {
        val registry = McpToolRegistry()
        val client = McpAuthenticatedClient("client-a", 7, setOf("prompts.write"), McpTransport.LOOPBACK)
        val persistence = InMemoryMutationReplayPersistence()
        val firstStore = McpMutationReplayStore(persistence)
        val invocation = mutation(registry, client, "persisted")
        var executions = 0

        firstStore.execute(client, invocation, "persisted") {
            executions += 1
            McpToolGatewayResult.Completed(JSONObject().put("promptId", "persisted"))
        }
        val replay = McpMutationReplayStore(persistence).execute(client, invocation, "persisted") {
            executions += 1
            McpToolGatewayResult.Completed(JSONObject().put("promptId", "duplicate"))
        } as McpToolGatewayResult.Completed

        assertEquals("persisted", replay.result.getString("promptId"))
        assertEquals(1, executions)
    }

    @Test
    fun replayStorePartitionsSameIdempotencyKeyByClientAndCredentialGeneration() {
        val registry = McpToolRegistry()
        val firstClient = McpAuthenticatedClient("client-a", 1, setOf("prompts.write"), McpTransport.LOOPBACK)
        val reissuedClient = firstClient.copy(tokenGeneration = 2)
        val otherClient = firstClient.copy(clientId = "client-b")
        val store = McpMutationReplayStore()
        var executions = 0

        listOf(firstClient, reissuedClient, otherClient).forEach { client ->
            val result = store.execute(client, mutation(registry, client, "shared-key"), "shared-key") {
                executions += 1
                McpToolGatewayResult.Completed(JSONObject().put("promptId", client.clientId + client.tokenGeneration))
            } as McpToolGatewayResult.Completed
            assertEquals(client.clientId + client.tokenGeneration, result.result.getString("promptId"))
        }
        assertEquals(3, executions)
        assertEquals(
            "client-a1",
            (
                store.execute(firstClient, mutation(registry, firstClient, "shared-key"), "shared-key") {
                    executions += 1
                    McpToolGatewayResult.Completed(JSONObject().put("promptId", "duplicate"))
                } as McpToolGatewayResult.Completed
                ).result.getString("promptId"),
        )
        assertEquals(3, executions)
    }

    @Test
    fun replayStoreExecutesOneMutationOnceAndRejectsReusedKeysForDifferentPayloads() {
        val registry = McpToolRegistry()
        val client = McpAuthenticatedClient("client-a", 1, setOf("prompts.write"), McpTransport.LOOPBACK)
        val store = McpMutationReplayStore()
        val initial = registry.validate(
            "prompts.create",
            JSONObject().put("title", "Title").put("prompt", "Prompt").put("idempotencyKey", "create-1"),
            client.scopes,
        ) as McpToolValidation.Allowed
        var executions = 0

        repeat(2) {
            val result = store.execute(client, initial.invocation, "create-1") {
                executions += 1
                McpToolGatewayResult.Completed(JSONObject().put("promptId", "prompt-$executions"))
            } as McpToolGatewayResult.Completed
            assertEquals("prompt-1", result.result.getString("promptId"))
        }
        assertEquals(1, executions)

        val changed = registry.validate(
            "prompts.create",
            JSONObject().put("title", "Changed").put("prompt", "Prompt").put("idempotencyKey", "create-1"),
            client.scopes,
        ) as McpToolValidation.Allowed
        assertEquals(
            "IDEMPOTENCY_KEY_CONFLICT",
            (
                store.execute(client, changed.invocation, "create-1") {
                    McpToolGatewayResult.Completed(JSONObject())
                } as McpToolGatewayResult.Rejected
                ).code,
        )
    }

    @Test
    fun nestedJsonMutationRetriesReplayDespiteObjectKeyOrderButRejectContentChanges() {
        val registry = McpToolRegistry()
        val client = McpAuthenticatedClient("client-a", 1, setOf("presets.write"), McpTransport.LOOPBACK)
        val store = McpMutationReplayStore()
        val first = registry.validate(
            "presets.import",
            JSONObject().put(
                "envelope",
                JSONObject()
                    .put("format", "v1")
                    .put("preset", JSONObject().put("selector", "fast").put("steps", 20)),
            ).put("idempotencyKey", "import-1"),
            client.scopes,
        ) as McpToolValidation.Allowed
        val reordered = registry.validate(
            "presets.import",
            JSONObject().put(
                "envelope",
                JSONObject()
                    .put("preset", JSONObject().put("steps", 20).put("selector", "fast"))
                    .put("format", "v1"),
            ).put("idempotencyKey", "import-1"),
            client.scopes,
        ) as McpToolValidation.Allowed
        var executions = 0

        store.execute(client, first.invocation, "import-1") {
            executions += 1
            McpToolGatewayResult.Completed(JSONObject().put("imported", true))
        }
        assertTrue(
            store.execute(client, reordered.invocation, "import-1") {
                executions += 1
                McpToolGatewayResult.Completed(JSONObject())
            } is McpToolGatewayResult.Completed,
        )
        assertEquals(1, executions)

        val changed = registry.validate(
            "presets.import",
            JSONObject().put("envelope", JSONObject().put("format", "v1").put("preset", JSONObject().put("selector", "fast").put("steps", 21)))
                .put("idempotencyKey", "import-1"),
            client.scopes,
        ) as McpToolValidation.Allowed
        assertEquals(
            "IDEMPOTENCY_KEY_CONFLICT",
            (store.execute(client, changed.invocation, "import-1") { McpToolGatewayResult.Completed(JSONObject()) } as McpToolGatewayResult.Rejected).code,
        )
    }

    @Test
    fun mutationToolsRequireANonBlankIdempotencyKeyAndDestructiveToolsRequireExplicitDryRun() {
        val registry = McpToolRegistry()

        assertEquals(
            "INVALID_PARAMS",
            (
                registry.validate(
                    "prompts.create",
                    JSONObject().put("title", "Title").put("prompt", "Prompt"),
                    setOf("prompts.write"),
                ) as McpToolValidation.Rejected
                ).code,
        )
        assertTrue(
            registry.validate(
                "prompts.create",
                JSONObject()
                    .put("title", "Title")
                    .put("prompt", "Prompt")
                    .put("idempotencyKey", "create-prompt-1"),
                setOf("prompts.write"),
            ) is McpToolValidation.Allowed,
        )
        assertEquals(
            "INVALID_PARAMS",
            (
                registry.validate(
                    "prompts.delete",
                    JSONObject().put("promptId", "prompt-1").put("idempotencyKey", "delete-prompt-1"),
                    setOf("prompts.write"),
                ) as McpToolValidation.Rejected
                ).code,
        )
        assertTrue(
            registry.validate(
                "prompts.delete",
                JSONObject()
                    .put("promptId", "prompt-1")
                    .put("idempotencyKey", "delete-prompt-1")
                    .put("dryRun", true),
                setOf("prompts.write"),
            ) is McpToolValidation.Allowed,
        )
    }

    @Test
    fun declaredJsonArgumentTypesAreEnforcedBeforeDomainDispatch() {
        val registry = McpToolRegistry()

        assertEquals(
            McpToolValidation.Rejected("INVALID_PARAMS"),
            registry.validate(
                "generation.create",
                JSONObject().put("modelId", "model-a").put("prompt", "p").put("steps", "20").put("idempotencyKey", "generation-1"),
                setOf("generation.run"),
            ),
        )
        assertEquals(
            McpToolValidation.Rejected("INVALID_PARAMS"),
            registry.validate(
                "presets.import",
                JSONObject().put("envelope", "not-an-object").put("idempotencyKey", "import-1"),
                setOf("presets.write"),
            ),
        )
    }

    @Test
    fun qualificationEvidenceImportToolIsNotRegistered() {
        val registry = McpToolRegistry()
        assertEquals(
            McpToolValidation.Rejected("TOOL_NOT_FOUND"),
            registry.validate(
                "presets.import_qualification_evidence",
                JSONObject(),
                setOf("qualifications.write"),
            ),
        )
        assertTrue("qualifications.write" !in McpGenerationGateway.DEFAULT_CLIENT_SCOPES)
    }

    @Test
    fun rejectsUnknownToolExtraFieldsAndMissingScopeBeforeDomainDispatch() {
        val registry = McpToolRegistry()

        assertEquals(
            McpToolValidation.Rejected("TOOL_NOT_FOUND"),
            registry.validate("shell.exec", JSONObject(), setOf("models.read")),
        )
        assertEquals(
            McpToolValidation.Rejected("INVALID_PARAMS"),
            registry.validate("jobs.cancel", JSONObject().put("jobId", "job-1").put("idempotencyKey", "cancel-1").put("dryRun", false).put("extra", true), setOf("jobs.write")),
        )
        assertEquals(
            McpToolValidation.Rejected("SCOPE_DENIED"),
            registry.validate("jobs.cancel", JSONObject().put("jobId", "job-1").put("idempotencyKey", "cancel-1").put("dryRun", false), emptySet()),
        )
        assertEquals(
            McpToolValidation.Rejected("INVALID_PARAMS"),
            registry.validate(
                "downloads.create",
                JSONObject().put("modelId", "model-a").put("url", "https://example.test/archive.zip"),
                setOf("downloads.write"),
            ),
        )
    }

    @Test
    fun destructiveInvocationHasStableDigestAndTargetForConfirmationBinding() {
        val registry = McpToolRegistry()

        val first = registry.validate("jobs.cancel", JSONObject().put("jobId", "job-1").put("idempotencyKey", "cancel-1").put("dryRun", false), setOf("jobs.write"))
        val second = registry.validate("jobs.cancel", JSONObject().put("jobId", "job-1").put("idempotencyKey", "cancel-1").put("dryRun", false), setOf("jobs.write"))

        assertTrue(first is McpToolValidation.Allowed)
        assertTrue(second is McpToolValidation.Allowed)
        assertEquals((first as McpToolValidation.Allowed).invocation, (second as McpToolValidation.Allowed).invocation)
        assertEquals(McpToolRisk.DESTRUCTIVE, first.invocation.definition.risk)
        assertEquals(setOf("job-1"), first.invocation.targetIds)

        val downloadCancel = registry.validate(
            "downloads.cancel",
            JSONObject().put("downloadId", "model-a").put("idempotencyKey", "cancel-download-1").put("dryRun", false),
            setOf("downloads.write"),
        )
        assertTrue(downloadCancel is McpToolValidation.Allowed)
        assertEquals(McpToolRisk.DESTRUCTIVE, (downloadCancel as McpToolValidation.Allowed).invocation.definition.risk)
        assertEquals(setOf("model-a"), downloadCancel.invocation.targetIds)
    }

    @Test
    fun presetUpdateRequiresOptimisticRevisionBeforeItCanReachDomain() {
        val registry = McpToolRegistry()

        assertEquals(
            McpToolValidation.Rejected("INVALID_PARAMS"),
            registry.validate("presets.update", JSONObject().put("presetId", "preset-1"), setOf("presets.write")),
        )
        assertTrue(
            registry.validate(
                "presets.update",
                JSONObject().put("presetId", "preset-1").put("revision", 1).put("idempotencyKey", "update-preset-1"),
                setOf("presets.write"),
            ) is McpToolValidation.Allowed,
        )
    }

    @Test
    fun generationRequiresOnlyModelAndPromptAndAllowsThePublishedW7Fixture() {
        val registry = McpToolRegistry()
        val minimal = JSONObject()
            .put("modelId", "model-a")
            .put("prompt", "portrait reference")
            .put("idempotencyKey", "generation-1")
        val w7 = JSONObject(minimal.toString())
            .put("negativePrompt", "low quality")
            .put("seed", 123456)
            .put("width", 1024)
            .put("height", 1024)
            .put("scheduler", "euler_a")
            .put("steps", 20)
            .put("cfg", 7)
            .put("denoiseStrength", 1.0)

        assertTrue(registry.validate("generation.create", minimal, setOf("generation.run")) is McpToolValidation.Allowed)
        assertTrue(registry.validate("generation.create", w7, setOf("generation.run")) is McpToolValidation.Allowed)
        assertEquals(
            McpToolValidation.Rejected("INVALID_PARAMS"),
            registry.validate("generation.create", JSONObject().put("modelId", "model-a"), setOf("generation.run")),
        )
        assertEquals(
            McpToolValidation.Rejected("INVALID_PARAMS"),
            registry.validate("generation.create", JSONObject(w7.toString()).put("path", "/sdcard/model"), setOf("generation.run")),
        )
    }

    private fun mutation(registry: McpToolRegistry, client: McpAuthenticatedClient, key: String): McpToolInvocation = (
        registry.validate(
            "prompts.create",
            JSONObject().put("title", "Title $key").put("prompt", "Prompt").put("idempotencyKey", key),
            client.scopes,
        ) as McpToolValidation.Allowed
        ).invocation

    private class InMemoryMutationReplayPersistence : McpMutationReplayPersistence {
        private val records = mutableMapOf<String, String>()

        override fun read(key: String): String? = records[key]

        override fun write(key: String, value: String): Boolean {
            records[key] = value
            return true
        }

        override fun remove(key: String): Boolean = records.remove(key) != null

        override fun writeWithinCapacity(key: String, value: String, cutoffMillis: Long, maxEntries: Int): Boolean {
            records.entries.removeIf { (_, stored) -> JSONObject(stored).getLong("recordedAt") < cutoffMillis }
            if (key !in records && records.size >= maxEntries) return false
            records[key] = value
            return true
        }
    }
}
