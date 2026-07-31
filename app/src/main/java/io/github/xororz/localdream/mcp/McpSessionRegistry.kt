package io.github.xororz.localdream.mcp

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory MCP session ownership boundary. Sessions intentionally disappear
 * on process/service restart and are never reusable across a token rotation.
 */
class McpSessionRegistry(
    private val clock: () -> Long = System::currentTimeMillis,
    private val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS,
) {
    private val sessions = ConcurrentHashMap<String, McpSession>()
    private val random = SecureRandom()

    init {
        require(idleTimeoutMillis > 0) { "idleTimeoutMillis must be positive" }
    }

    fun create(
        clientId: String,
        tokenGeneration: Long,
        transport: McpTransport,
        scopes: Set<String>,
    ): McpSession {
        val now = clock()
        val session = McpSession(
            id = newId(),
            clientId = clientId,
            tokenGeneration = tokenGeneration,
            transport = transport,
            scopes = scopes.toSet(),
            createdAt = now,
            lastActivityAt = now,
        )
        sessions[session.id] = session
        return session
    }

    fun validate(
        sessionId: String,
        clientId: String,
        tokenGeneration: Long,
        transport: McpTransport,
    ): McpSession? {
        val current = sessions[sessionId] ?: return null
        val now = clock()
        if (current.clientId != clientId ||
            current.tokenGeneration != tokenGeneration ||
            current.transport != transport ||
            now - current.lastActivityAt >= idleTimeoutMillis
        ) {
            sessions.remove(sessionId, current)
            return null
        }
        val touched = current.copy(lastActivityAt = now)
        sessions.replace(sessionId, current, touched)
        return touched
    }

    fun remove(sessionId: String): Boolean = sessions.remove(sessionId) != null

    fun removeForTransport(transport: McpTransport): Int {
        val removed = sessions.entries.removeIf { it.value.transport == transport }
        return if (removed) 1 else 0
    }

    fun invalidateClient(clientId: String): Int {
        val removed = sessions.entries.removeIf { it.value.clientId == clientId }
        return if (removed) 1 else 0
    }

    fun sessionIdsForClient(clientId: String): List<String> = sessions.values
        .filter { it.clientId == clientId }
        .map(McpSession::id)

    /** Checks stream ownership without extending its idle timeout. */
    fun isActive(
        sessionId: String,
        clientId: String,
        tokenGeneration: Long,
        transport: McpTransport,
    ): Boolean {
        val current = sessions[sessionId] ?: return false
        val active = current.clientId == clientId &&
            current.tokenGeneration == tokenGeneration &&
            current.transport == transport &&
            clock() - current.lastActivityAt < idleTimeoutMillis
        if (!active) sessions.remove(sessionId, current)
        return active
    }

    /**
     * Renews only an already-authorized stream lease. It repeats ownership
     * checks so a heartbeat cannot revive a revoked token or foreign session.
     */
    fun renewLease(
        sessionId: String,
        clientId: String,
        tokenGeneration: Long,
        transport: McpTransport,
    ): Boolean {
        while (true) {
            val current = sessions[sessionId] ?: return false
            val now = clock()
            if (current.clientId != clientId ||
                current.tokenGeneration != tokenGeneration ||
                current.transport != transport ||
                now - current.lastActivityAt >= idleTimeoutMillis
            ) {
                sessions.remove(sessionId, current)
                return false
            }
            if (sessions.replace(sessionId, current, current.copy(lastActivityAt = now))) return true
        }
    }

    fun sessionsFor(clientId: String, transport: McpTransport): List<McpSession> {
        val now = clock()
        sessions.entries.removeIf { (_, session) -> now - session.lastActivityAt >= idleTimeoutMillis }
        return sessions.values.filter { it.clientId == clientId && it.transport == transport }
    }

    private fun newId(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MILLIS = 15 * 60 * 1000L
    }
}

enum class McpTransport { LOOPBACK, LAN }

data class McpSession(
    val id: String,
    val clientId: String,
    val tokenGeneration: Long,
    val transport: McpTransport,
    val scopes: Set<String>,
    val createdAt: Long,
    val lastActivityAt: Long,
)
