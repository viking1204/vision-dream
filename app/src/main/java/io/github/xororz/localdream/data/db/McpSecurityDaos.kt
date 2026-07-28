package io.github.xororz.localdream.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface McpClientGrantDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(grant: McpClientGrantEntity)

    @Query("UPDATE mcp_client_grants SET revokedAt = :revokedAt WHERE id = :id")
    suspend fun revoke(id: String, revokedAt: Long): Int
}

@Dao
interface McpAuditEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun append(event: McpAuditEventEntity)

    @Query("SELECT * FROM mcp_audit_events WHERE clientId = :clientId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun listForClient(clientId: String, limit: Int): List<McpAuditEventEntity>

    @Query("SELECT * FROM mcp_audit_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun listRecent(limit: Int): List<McpAuditEventEntity>
}
