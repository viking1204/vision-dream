package io.github.xororz.localdream.mcp

import android.content.Context
import org.json.JSONObject

/** Private durable storage for MCP mutation replay records; it never stores bearer tokens. */
interface McpMutationReplayPersistence {
    fun read(key: String): String?
    fun write(key: String, value: String): Boolean
    fun remove(key: String): Boolean

    /** Atomically prunes expired records then admits a new record only if safe tombstones fit. */
    fun writeWithinCapacity(key: String, value: String, cutoffMillis: Long, maxEntries: Int): Boolean
}

/**
 * Shares one app-private replay ledger across loopback and LAN listeners and
 * across service recreation. `commit` makes the IN_FLIGHT fence durable before
 * a mutation runs; a failed commit is fail-closed in McpMutationReplayStore.
 */
class AndroidMcpMutationReplayPersistence(context: Context) : McpMutationReplayPersistence {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun read(key: String): String? = preferences.getString(prefix(key), null)

    override fun write(key: String, value: String): Boolean = preferences.edit().putString(prefix(key), value).commit()

    override fun remove(key: String): Boolean = preferences.edit().remove(prefix(key)).commit()

    override fun writeWithinCapacity(key: String, value: String, cutoffMillis: Long, maxEntries: Int): Boolean = synchronized(preferences) {
        val expired = preferences.all
            .filterKeys { it.startsWith(ENTRY_PREFIX) }
            .filterValues { value ->
                runCatching { JSONObject(value as String).getLong("recordedAt") < cutoffMillis }.getOrDefault(true)
            }
            .keys
        if (expired.isNotEmpty() && !preferences.edit().apply { expired.forEach(::remove) }.commit()) return@synchronized false
        val storedKey = prefix(key)
        if (!preferences.contains(storedKey) && preferences.all.keys.count { it.startsWith(ENTRY_PREFIX) } >= maxEntries) return@synchronized false
        preferences.edit().putString(storedKey, value).commit()
    }

    private fun prefix(key: String) = "$ENTRY_PREFIX$key"

    private companion object {
        const val PREFERENCES = "mcp_mutation_replays"
        const val ENTRY_PREFIX = "entry."
    }
}
