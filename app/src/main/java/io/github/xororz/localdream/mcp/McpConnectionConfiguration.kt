package io.github.xororz.localdream.mcp

/**
 * Renders the one-time MCP client configuration after an unlocked user explicitly
 * requests it. The caller owns its lifetime and must not persist the result.
 */
object McpConnectionConfiguration {
    fun render(host: String, port: Int, token: String, scopes: Set<String>): String {
        val normalizedHost = host.trim().lowercase()
        if (!isSafeHost(normalizedHost) || token.isBlank()) return ""
        if (runCatching { McpHttpServer.validatePort(port) }.isFailure) return ""
        return buildString {
            appendLine("url: http://$normalizedHost:$port${McpProtocol.PATH}")
            appendLine("protocolVersion: ${McpProtocol.VERSION}")
            appendLine("authorization: Bearer $token")
            append("scopes: ${scopes.sorted().joinToString(" ")}")
        }
    }

    private fun isSafeHost(host: String): Boolean = host == "127.0.0.1" ||
        McpLanHostAllowlist.normalize(host) != null
}
