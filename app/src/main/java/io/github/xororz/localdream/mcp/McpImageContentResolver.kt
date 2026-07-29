package io.github.xororz.localdream.mcp

/**
 * Resolves an authenticated MCP asset id. Implementations must validate the
 * id format and must never accept client-supplied file paths.
 */
fun interface McpImageContentResolver {
    fun resolve(assetId: String): McpImageContent?
}

data class McpImageContent(
    val bytes: ByteArray,
    val mimeType: String,
)
