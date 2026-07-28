package io.github.xororz.localdream.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSessionRegistryTest {
    @Test
    fun sessionIsBoundToClientTokenGenerationAndTransport() {
        val registry = McpSessionRegistry(clock = { 1_000L })
        val session = registry.create(
            clientId = "loopback-client",
            tokenGeneration = 3,
            transport = McpTransport.LOOPBACK,
            scopes = setOf("models.read"),
        )

        assertTrue(
            registry.validate(
                sessionId = session.id,
                clientId = "loopback-client",
                tokenGeneration = 3,
                transport = McpTransport.LOOPBACK,
            ) != null,
        )
        assertNull(
            registry.validate(session.id, "loopback-client", 4, McpTransport.LOOPBACK),
        )
        assertNull(
            registry.validate(session.id, "loopback-client", 3, McpTransport.LAN),
        )
    }

    @Test
    fun expiredAndDeletedSessionsCannotResume() {
        var now = 1_000L
        val registry = McpSessionRegistry(clock = { now }, idleTimeoutMillis = 100L)
        val session = registry.create("client", 1, McpTransport.LOOPBACK, emptySet())

        now += 101L
        assertNull(registry.validate(session.id, "client", 1, McpTransport.LOOPBACK))
        assertEquals(0, registry.removeForTransport(McpTransport.LOOPBACK))
    }
}
