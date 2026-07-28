package io.github.xororz.localdream.mcp

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 逐次本机确认的短期内存凭据。
 *
 * 远端工具调用只能提出待确认请求；本机 UI 审核请求的绑定信息后才会签发
 * confirmationId。服务重启会清空两类状态，不能把一次同意带到新 listener。
 */
class McpConfirmationStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = ::newId,
    private val requestIdGenerator: () -> String = ::newId,
) {
    private val pending = ConcurrentHashMap<String, PendingConfirmation>()
    private val invalidated = ConcurrentHashMap.newKeySet<String>()
    private val pendingUiRequests = ConcurrentHashMap<String, PendingUiConfirmation>()
    private val _uiRequests = MutableStateFlow<List<McpPendingConfirmation>>(emptyList())

    /** 供本机管理 UI 观察，只包含绑定摘要，绝不包含远端原始参数或 prompt。 */
    val uiRequests: StateFlow<List<McpPendingConfirmation>> = _uiRequests

    fun issue(request: McpConfirmationRequest): String {
        val id = idGenerator().also { require(it.isNotBlank()) { "Confirmation ID is required" } }
        pending[id] = PendingConfirmation(request, clock() + TTL_MILLIS)
        return id
    }

    /**
     * 由 listener 在破坏性调用缺少有效 confirmationId 时登记。重复重试复用
     * 同一待确认项，防止远端重试淹没本机 UI。
     */
    @Synchronized
    fun requestUiConfirmation(request: McpConfirmationRequest): McpPendingConfirmation {
        pruneExpired()
        pendingUiRequests.values.firstOrNull { it.request == request }?.let { return it.toPublic() }
        val pendingRequest = PendingUiConfirmation(
            id = requestIdGenerator().also { require(it.isNotBlank()) { "Confirmation request ID is required" } },
            request = request,
            expiresAt = clock() + TTL_MILLIS,
        )
        pendingUiRequests[pendingRequest.id] = pendingRequest
        publishUiRequests()
        return pendingRequest.toPublic()
    }

    /**
     * 仅由本机 UI 调用。签发后请求立即移出列表，confirmationId 仍只能匹配
     * 原 client/token generation/action/digest/target 的下一次工具重试。
     */
    @Synchronized
    fun approveUiRequest(requestId: String): String? {
        pruneExpired()
        val pendingRequest = pendingUiRequests.remove(requestId) ?: return null
        publishUiRequests()
        return issue(pendingRequest.request)
    }

    /** 本机明确拒绝；同一远端重试会作为新的请求再次展示。 */
    @Synchronized
    fun rejectUiRequest(requestId: String) {
        if (pendingUiRequests.remove(requestId) != null) publishUiRequests()
    }

    @Synchronized
    fun clear() {
        pending.clear()
        invalidated.clear()
        pendingUiRequests.clear()
        publishUiRequests()
    }

    fun consume(id: String?, request: McpConfirmationRequest): McpConfirmationResult {
        if (id.isNullOrBlank()) return McpConfirmationResult.REQUIRED
        val confirmation = pending[id]
        if (confirmation == null) {
            return if (wasInvalidated(id)) {
                McpConfirmationResult.INVALID
            } else {
                McpConfirmationResult.REQUIRED
            }
        }
        if (clock() > confirmation.expiresAt || confirmation.request != request) {
            pending.remove(id, confirmation)
            invalidated += id
            return McpConfirmationResult.INVALID
        }
        return if (pending.remove(id, confirmation)) {
            invalidated += id
            McpConfirmationResult.APPROVED
        } else {
            McpConfirmationResult.INVALID
        }
    }

    private fun wasInvalidated(id: String): Boolean = id in invalidated

    @Synchronized
    private fun pruneExpired() {
        val now = clock()
        val removed = pendingUiRequests.entries.removeIf { (_, request) -> now > request.expiresAt }
        if (removed) publishUiRequests()
    }

    private fun publishUiRequests() {
        _uiRequests.value = pendingUiRequests.values
            .sortedBy(PendingUiConfirmation::createdAt)
            .map(PendingUiConfirmation::toPublic)
    }

    companion object {
        const val TTL_MILLIS = 60_000L

        private fun newId(): String = ByteArray(32).also(SecureRandom()::nextBytes).let { bytes ->
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}

data class McpConfirmationRequest(
    val clientId: String,
    val tokenGeneration: Long,
    val action: String,
    val parameterDigest: String,
    val targetIds: Set<String>,
    val scopes: Set<String> = emptySet(),
)

private data class PendingConfirmation(val request: McpConfirmationRequest, val expiresAt: Long)

private data class PendingUiConfirmation(
    val id: String,
    val request: McpConfirmationRequest,
    val expiresAt: Long,
    val createdAt: Long = System.nanoTime(),
) {
    fun toPublic(): McpPendingConfirmation = McpPendingConfirmation(
        id = id,
        clientId = request.clientId,
        action = request.action,
        targetIds = request.targetIds,
        scopes = request.scopes,
        parameterDigest = request.parameterDigest,
        expiresAt = expiresAt,
    )
}

/** 可安全渲染在本机 UI 的待确认摘要；不暴露原始调用参数或 prompt。 */
data class McpPendingConfirmation(
    val id: String,
    val clientId: String,
    val action: String,
    val targetIds: Set<String>,
    val scopes: Set<String>,
    val parameterDigest: String,
    val expiresAt: Long,
)

enum class McpConfirmationResult { REQUIRED, APPROVED, INVALID }
