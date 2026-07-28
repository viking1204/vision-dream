package io.github.xororz.localdream.mcp

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP 图片下载能力只保存不透明 asset 标识，绝不保存文件绝对路径或图片字节。
 * 成功取得 capability 时原子移除，以保证一次性下载语义。
 */
class McpImageCapabilityStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val tokenGenerator: () -> String = ::newToken,
) {
    private val capabilities = ConcurrentHashMap<String, McpImageCapability>()

    fun create(clientId: String, jobId: String, transport: McpTransport, mimeType: String, assetId: String): McpImageCapability {
        val capability = McpImageCapability(tokenGenerator(), clientId, jobId, transport, mimeType, assetId, clock() + TTL_MILLIS)
        capabilities[capability.token] = capability
        return capability
    }

    fun peek(token: String, clientId: String, jobId: String, transport: McpTransport): McpImageCapability? {
        val capability = capabilities[token] ?: return null
        if (clock() > capability.expiresAt || capability.clientId != clientId || capability.jobId != jobId || capability.transport != transport) {
            if (clock() > capability.expiresAt) capabilities.remove(token, capability)
            return null
        }
        return capability
    }

    fun consume(token: String, clientId: String, jobId: String, transport: McpTransport): McpImageCapability? {
        val capability = peek(token, clientId, jobId, transport) ?: return null
        return capability.takeIf { capabilities.remove(token, capability) }
    }

    companion object {
        const val TTL_MILLIS = 60_000L

        private fun newToken(): String = ByteArray(32).also(SecureRandom()::nextBytes).let { bytes ->
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}

data class McpImageCapability(
    val token: String,
    val clientId: String,
    val jobId: String,
    val transport: McpTransport,
    val mimeType: String,
    val assetId: String,
    val expiresAt: Long,
)
