package io.github.xororz.localdream.mcp

import org.junit.Assert.assertEquals
import org.junit.Test

class McpConfirmationStoreTest {
    @Test
    fun confirmationIsSingleUseAndBoundToClientGenerationActionDigestAndTargets() {
        var now = 1_000L
        val store = McpConfirmationStore(clock = { now }, idGenerator = { "confirmation-1" })
        val request = confirmationRequest()
        val id = store.issue(request)

        assertEquals(McpConfirmationResult.APPROVED, store.consume(id, request))
        assertEquals(McpConfirmationResult.INVALID, store.consume(id, request))
    }

    @Test
    fun confirmationRejectsExpiryAndAnyBindingMismatch() {
        var now = 1_000L
        val store = McpConfirmationStore(clock = { now }, idGenerator = { "confirmation-1" })
        val request = confirmationRequest()
        val id = store.issue(request)

        assertEquals(
            McpConfirmationResult.INVALID,
            store.consume(id, request.copy(tokenGeneration = 2)),
        )
        now += McpConfirmationStore.TTL_MILLIS + 1
        assertEquals(McpConfirmationResult.INVALID, store.consume(id, request))
    }

    @Test
    fun uiApprovalIssuesOneIdForTheExactPendingRequestAndExposesOnlyItsSafeBindingSummary() {
        val store = McpConfirmationStore(
            idGenerator = { "confirmation-1" },
            requestIdGenerator = { "ui-request-1" },
        )
        val request = confirmationRequest()

        val pending = store.requestUiConfirmation(request)

        assertEquals("ui-request-1", pending.id)
        assertEquals("client-a", pending.clientId)
        assertEquals("jobs.cancel", pending.action)
        assertEquals(setOf("job-1"), pending.targetIds)
        assertEquals("digest", pending.parameterDigest)
        assertEquals(setOf("jobs.write"), pending.scopes)
        assertEquals("confirmation-1", store.approveUiRequest(pending.id))
        assertEquals(emptyList<McpPendingConfirmation>(), store.uiRequests.value)
        assertEquals(McpConfirmationResult.APPROVED, store.consume("confirmation-1", request))
    }

    private fun confirmationRequest() = McpConfirmationRequest(
        clientId = "client-a",
        tokenGeneration = 1,
        action = "jobs.cancel",
        parameterDigest = "digest",
        targetIds = setOf("job-1"),
        scopes = setOf("jobs.write"),
    )
}
