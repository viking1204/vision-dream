package io.github.xororz.localdream.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McpLanHostAllowlistTest {
    @Test
    fun normalizesTrustedDnsAndIpHostsWithoutPortsOrSchemes() {
        assertEquals("studio.local", McpLanHostAllowlist.normalize(" Studio.Local "))
        assertEquals("192.168.1.25", McpLanHostAllowlist.normalize("192.168.1.25"))
        assertEquals("[fd00::1]", McpLanHostAllowlist.normalize("[fd00::1]"))
    }

    @Test
    fun rejectsLoopbackWildcardAndUrlLikeHosts() {
        listOf("", "localhost", "localhost.", "127.0.0.1", "127.0.0.2", "::1", "0.0.0.0", "*.local", "host:8810", "https://host")
            .forEach { host -> assertNull("host=$host", McpLanHostAllowlist.normalize(host)) }
    }

    @Test
    fun replaceCanonicalizesDeduplicatesAndDropsInvalidHosts() {
        assertEquals(
            setOf("studio.local", "192.168.1.25"),
            McpLanHostAllowlist.canonicalize(listOf("Studio.Local", "studio.local", "192.168.1.25", "localhost")),
        )
    }
}
