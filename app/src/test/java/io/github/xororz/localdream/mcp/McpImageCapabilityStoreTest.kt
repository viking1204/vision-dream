package io.github.xororz.localdream.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McpImageCapabilityStoreTest {
    @Test
    fun capabilityIsBoundToClientJobTransportAndConsumedAtFirstRead() {
        val store = McpImageCapabilityStore(clock = { 1_000L }, tokenGenerator = { "capability-1" })
        val capability = store.create("client-a", "job-1", McpTransport.LOOPBACK, "image/png", "asset-1")

        assertNull(store.consume(capability.token, "client-b", "job-1", McpTransport.LOOPBACK))
        assertEquals(capability, store.consume(capability.token, "client-a", "job-1", McpTransport.LOOPBACK))
        assertNull(store.consume(capability.token, "client-a", "job-1", McpTransport.LOOPBACK))
    }

    @Test
    fun expiredCapabilityIsNotReadable() {
        var now = 1_000L
        val store = McpImageCapabilityStore(clock = { now }, tokenGenerator = { "capability-1" })
        val capability = store.create("client-a", "job-1", McpTransport.LOOPBACK, "image/png", "asset-1")

        now += McpImageCapabilityStore.TTL_MILLIS + 1
        assertNull(store.consume(capability.token, "client-a", "job-1", McpTransport.LOOPBACK))
    }
}
