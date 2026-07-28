package io.github.xororz.localdream.mcp

import android.content.Context

/** Persisted MCP-only listener choices; OpenAI preferences are never read here. */
class McpServerPreferences(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun port(): Int = preferences.getInt(PORT, McpHttpServer.DEFAULT_PORT)
        .takeIf { runCatching { McpHttpServer.validatePort(it) }.isSuccess }
        ?: McpHttpServer.DEFAULT_PORT

    fun setPort(port: Int) {
        McpHttpServer.validatePort(port)
        preferences.edit().putInt(PORT, port).apply()
    }

    fun lanEnabled(): Boolean = preferences.getBoolean(LAN_ENABLED, false)

    fun setLanEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(LAN_ENABLED, enabled).apply()
    }

    private companion object {
        const val PREFERENCES = "mcp_server"
        const val PORT = "port"
        const val LAN_ENABLED = "lan_enabled"
    }
}
