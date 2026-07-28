package io.github.xororz.localdream.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpAuthorizationTest {
    @Test
    fun rejectsUnknownToolExtraFieldsAndMissingScopeBeforeDomainDispatch() {
        val registry = McpToolRegistry()

        assertEquals(
            McpToolValidation.Rejected("TOOL_NOT_FOUND"),
            registry.validate("shell.exec", JSONObject(), setOf("models.read")),
        )
        assertEquals(
            McpToolValidation.Rejected("INVALID_PARAMS"),
            registry.validate("jobs.cancel", JSONObject().put("jobId", "job-1").put("extra", true), setOf("jobs.write")),
        )
        assertEquals(
            McpToolValidation.Rejected("SCOPE_DENIED"),
            registry.validate("jobs.cancel", JSONObject().put("jobId", "job-1"), emptySet()),
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

        val first = registry.validate("jobs.cancel", JSONObject().put("jobId", "job-1"), setOf("jobs.write"))
        val second = registry.validate("jobs.cancel", JSONObject().put("jobId", "job-1"), setOf("jobs.write"))

        assertTrue(first is McpToolValidation.Allowed)
        assertTrue(second is McpToolValidation.Allowed)
        assertEquals((first as McpToolValidation.Allowed).invocation, (second as McpToolValidation.Allowed).invocation)
        assertEquals(McpToolRisk.DESTRUCTIVE, first.invocation.definition.risk)
        assertEquals(setOf("job-1"), first.invocation.targetIds)

        val downloadCancel = registry.validate(
            "downloads.cancel",
            JSONObject().put("downloadId", "model-a"),
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
                JSONObject().put("presetId", "preset-1").put("revision", 1),
                setOf("presets.write"),
            ) is McpToolValidation.Allowed,
        )
    }
}
