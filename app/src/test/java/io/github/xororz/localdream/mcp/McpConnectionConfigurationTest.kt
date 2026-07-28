package io.github.xororz.localdream.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpConnectionConfigurationTest {
    @Test
    fun rendersOnlyTheApprovedConnectionFieldsInStableOrder() {
        val configuration = McpConnectionConfiguration.render(
            host = "127.0.0.1",
            port = 8810,
            token = "secret-token",
            scopes = setOf("generation.run", "models.read"),
        )

        assertEquals(
            """
            |url: http://127.0.0.1:8810/mcp
            |protocolVersion: 2025-11-25
            |authorization: Bearer secret-token
            |scopes: generation.run models.read
            """.trimMargin(),
            configuration,
        )
        assertFalse(configuration.contains("clientId"))
        assertFalse(configuration.contains("confirmation"))
    }

    @Test
    fun rejectsUntrustedHostsAndInvalidPortsInsteadOfEmittingCopyableConfig() {
        assertTrue(McpConnectionConfiguration.render("https://host", 8810, "token", emptySet()).isEmpty())
        assertTrue(McpConnectionConfiguration.render("127.0.0.1", 8081, "token", emptySet()).isEmpty())
        assertTrue(McpConnectionConfiguration.render("127.0.0.1", 8810, "", emptySet()).isEmpty())
    }
}
