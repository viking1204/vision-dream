package io.github.xororz.localdream.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McpHttpServerAuthorityTest {
    @Test
    fun parsesDnsIpv4AndBracketedIpv6AuthoritiesWithoutDroppingIpv6Segments() {
        assertEquals("studio.local", McpHttpServer.parseAuthorityHost("Studio.Local:8810"))
        assertEquals("192.168.1.25", McpHttpServer.parseAuthorityHost("192.168.1.25:8810"))
        assertEquals("[fd00::1]", McpHttpServer.parseAuthorityHost("[FD00::1]:8810"))
        assertEquals("[fd00::1]", McpHttpServer.parseAuthorityHost("[fd00::1]"))
    }

    @Test
    fun rejectsMalformedOrAmbiguousAuthorities() {
        listOf("[fd00::1", "[fd00::1]suffix", "[fd00::1]:0", "fd00::1", "studio.local:bad")
            .forEach { authority -> assertNull("authority=$authority", McpHttpServer.parseAuthorityHost(authority)) }
    }
}
