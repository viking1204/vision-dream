package io.github.xororz.localdream.mcp

import android.content.Context
import io.github.xororz.localdream.data.db.AppDatabase
import io.github.xororz.localdream.data.db.McpAuditEventEntity
import io.github.xororz.localdream.data.db.McpClientGrantEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps credential material in Android Keystore-backed preferences while Room
 * stores only revocable grant metadata and the minimal audit projection.
 */
class AndroidMcpGrantRepository(context: Context) {
    private val credentials = McpClientCredentialStore(context)
    private val database = AppDatabase.get(context.applicationContext)

    suspend fun provision(
        clientId: String,
        transport: McpTransport,
        scopes: Set<String>,
    ): McpClientCredentialStore.ProvisionedCredential = withContext(Dispatchers.IO) {
        val credential = credentials.provision(clientId, transport, scopes)
        database.mcpClientGrantDao().upsert(
            McpClientGrantEntity(
                id = credential.clientId,
                clientId = credential.clientId,
                tokenAlias = "mcp_credentials.${credential.clientId}",
                tokenGeneration = credential.generation,
                scopesJson = credential.scopes.sorted().joinToString(" "),
                lanAllowed = credential.transport == McpTransport.LAN,
                createdAt = System.currentTimeMillis(),
                revokedAt = null,
            ),
        )
        credential
    }

    suspend fun grants(): List<McpClientCredentialStore.GrantSummary> = withContext(Dispatchers.IO) { credentials.grants() }

    suspend fun hasActiveGrant(clientId: String): Boolean = withContext(Dispatchers.IO) {
        credentials.grants().any { it.clientId == clientId }
    }

    /**
     * Re-issues an existing grant with the same device-administered transport
     * and scope. The new bearer intentionally remains local-only; callers get
     * only the state change and the UI can explicitly copy a fresh config.
     */
    suspend fun rotate(clientId: String): Boolean = withContext(Dispatchers.IO) {
        val current = credentials.grants().firstOrNull { it.clientId == clientId } ?: return@withContext false
        val credential = credentials.provision(current.clientId, current.transport, current.scopes)
        database.mcpClientGrantDao().upsert(
            McpClientGrantEntity(
                id = credential.clientId,
                clientId = credential.clientId,
                tokenAlias = "mcp_credentials.${credential.clientId}",
                tokenGeneration = credential.generation,
                scopesJson = credential.scopes.sorted().joinToString(" "),
                lanAllowed = credential.transport == McpTransport.LAN,
                createdAt = System.currentTimeMillis(),
                revokedAt = null,
            ),
        )
        true
    }

    suspend fun revoke(clientId: String) = withContext(Dispatchers.IO) {
        credentials.revoke(clientId)
        database.mcpClientGrantDao().revoke(clientId, System.currentTimeMillis())
    }

    suspend fun recentAudit(clientId: String, limit: Int = 10): List<McpAuditEventEntity> = withContext(Dispatchers.IO) {
        database.mcpAuditEventDao().listForClient(clientId, limit.coerceIn(1, 50))
    }
}
