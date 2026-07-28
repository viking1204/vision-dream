package io.github.xororz.localdream.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 不保存 Bearer 明文；tokenAlias 指向 Android Keystore，generation 用于撤销后失效。
 */
@Entity(
    tableName = "mcp_client_grants",
    indices = [Index(value = ["clientId"], unique = true)],
)
data class McpClientGrantEntity(
    @PrimaryKey val id: String,
    val clientId: String,
    val tokenAlias: String,
    val tokenGeneration: Long,
    val scopesJson: String,
    val lanAllowed: Boolean,
    val createdAt: Long,
    val revokedAt: Long?,
)

/** 最小化审计记录，不持久化 Token、confirmation、原始 prompt、图片或路径。 */
@Entity(
    tableName = "mcp_audit_events",
    indices = [Index(value = ["timestamp"]), Index(value = ["clientId", "timestamp"]), Index(value = ["jobId"])],
)
data class McpAuditEventEntity(
    @PrimaryKey val eventId: String,
    val timestamp: Long,
    val clientId: String,
    val transport: String,
    val sessionHash: String?,
    val method: String,
    val scopeSnapshot: String,
    val risk: String,
    val parameterDigest: String,
    val jobId: String?,
    val outcomeCode: String,
    val durationMs: Long,
)
