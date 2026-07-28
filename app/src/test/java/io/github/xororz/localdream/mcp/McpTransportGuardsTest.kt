package io.github.xororz.localdream.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpTransportGuardsTest {
    @Test
    fun rpcLimitRejectsBeforeASecondWindowAndResetsAtWindowBoundary() {
        var now = 0L
        val guards = McpTransportGuards(clock = { now }, rpcPerMinute = 2, concurrentSse = 2)

        assertNull(guards.takeRpc("client-a"))
        assertNull(guards.takeRpc("client-a"))
        assertEquals(60, guards.takeRpc("client-a"))
        now = 60_000L
        assertNull(guards.takeRpc("client-a"))
    }

    @Test
    fun sseLimitIsPerClientAndReleasesWhenTheConnectionCloses() {
        val guards = McpTransportGuards(rpcPerMinute = 1, concurrentSse = 2)

        assertNull(guards.openSse("client-a"))
        assertNull(guards.openSse("client-a"))
        assertEquals(1, guards.openSse("client-a"))
        assertNull(guards.openSse("client-b"))
        guards.closeSse("client-a")
        assertNull(guards.openSse("client-a"))
    }

    @Test
    fun replayReturnsMissedEventsAndEmitsResetAfterRetentionIsExceeded() {
        val store = McpSseEventStore()
        store.publish("session", "task", "{\"task\":\"working\"}")
        val replay = store.open("session", 0)
        try {
            assertEquals("task", replay.initial.single().event)
        } finally {
            replay.close()
        }

        repeat(129) { store.publish("session", "task", "{}") }
        val expired = store.open("session", 1)
        try {
            assertEquals("reset", expired.initial.single().event)
            assertTrue(expired.initial.single().data.contains("replay_unavailable"))
        } finally {
            expired.close()
        }
    }

    @Test
    fun closingSessionUnblocksAnOpenSseSubscription() {
        val store = McpSseEventStore()
        val subscription = store.open("session", null)

        store.close("session")

        assertEquals(McpSseEventStore.CLOSED_EVENT, subscription.poll(100))
        subscription.close()
    }
}
