package io.github.xororz.localdream.mcp

/**
 * Resolves the opaque asset selected by a capability after transport
 * authorization. Implementations must not accept client-supplied file paths.
 */
fun interface McpImageContentResolver {
    fun resolve(capability: McpImageCapability): McpImageContent?
}

data class McpImageContent(
    val bytes: ByteArray,
    val mimeType: String,
)
