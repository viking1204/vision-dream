package io.github.xororz.localdream.mcp

import io.github.xororz.localdream.data.db.McpAuditEventDao
import io.github.xororz.localdream.data.db.McpAuditEventEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking

/**
 * Minimal, sanitized record of an authenticated MCP request.  Raw JSON-RPC
 * arguments never cross this boundary: callers must provide only a canonical
 * parameter digest when a registered Tool has one.
 */
data class McpAuditEvent(
    val timestamp: Long,
    val clientId: String,
    val transport: McpTransport,
    val sessionHash: String?,
    val method: String,
    val scopeSnapshot: String,
    val risk: String,
    val parameterDigest: String,
    val jobId: String?,
    val outcomeCode: String,
    val durationMs: Long,
)

interface McpAuditSink {
    fun append(event: McpAuditEvent)

    object None : McpAuditSink {
        override fun append(event: McpAuditEvent) = Unit
    }
}

/**
 * Room adapter used by the foreground MCP listener.  Room suspend access is
 * bridged on the listener worker thread; no UI thread ever invokes this sink.
 */
class RoomMcpAuditSink(
    private val dao: McpAuditEventDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val eventId: () -> String = { UUID.randomUUID().toString() },
) : McpAuditSink {
    override fun append(event: McpAuditEvent) = runBlocking {
        dao.append(
            McpAuditEventEntity(
                eventId = eventId(),
                timestamp = event.timestamp.takeIf { it > 0 } ?: nowMillis(),
                clientId = event.clientId,
                transport = event.transport.name.lowercase(),
                sessionHash = event.sessionHash,
                method = event.method,
                scopeSnapshot = event.scopeSnapshot,
                risk = event.risk,
                parameterDigest = event.parameterDigest,
                jobId = event.jobId,
                outcomeCode = event.outcomeCode,
                durationMs = event.durationMs,
            ),
        )
    }
}
