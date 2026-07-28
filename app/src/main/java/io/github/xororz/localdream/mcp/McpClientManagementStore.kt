package io.github.xororz.localdream.mcp

import android.content.Context
import kotlinx.coroutines.runBlocking

/**
 * Product-domain operations for device-administered MCP credentials. Tokens
 * remain inside Android Keystore-backed storage and are never returned to an
 * MCP caller; the unlocked local UI is the only configuration-copy surface.
 */
interface McpClientManagementStore {
    fun revoke(clientId: String): Boolean
    fun rotate(clientId: String): Boolean

    object Unavailable : McpClientManagementStore {
        override fun revoke(clientId: String): Boolean = false
        override fun rotate(clientId: String): Boolean = false
    }
}

class AndroidMcpClientManagementStore(
    context: Context,
    private val invalidateSessions: (String) -> Unit,
) : McpClientManagementStore {
    private val grants = AndroidMcpGrantRepository(context.applicationContext)

    override fun revoke(clientId: String): Boolean = runBlocking {
        if (!grants.hasActiveGrant(clientId)) return@runBlocking false
        grants.revoke(clientId)
        invalidateSessions(clientId)
        true
    }

    override fun rotate(clientId: String): Boolean = runBlocking {
        if (!grants.rotate(clientId)) return@runBlocking false
        invalidateSessions(clientId)
        true
    }
}
