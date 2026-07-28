package io.github.xororz.localdream.mcp

import android.content.Context
import io.github.xororz.localdream.data.PerformancePreset
import io.github.xororz.localdream.data.PerformancePresetRepository
import io.github.xororz.localdream.data.PerformancePresetStore
import io.github.xororz.localdream.data.db.AppDatabase
import io.github.xororz.localdream.data.db.PerformancePresetDao
import io.github.xororz.localdream.data.db.PerformancePresetEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP-facing adapter for product performance presets. It deliberately accepts
 * structured preset fields only: neither a client file path nor arbitrary
 * runtime command can pass through this boundary.
 */
interface McpPresetStore {
    fun list(): List<PerformancePreset>
    fun get(id: String): PerformancePreset?
    fun create(name: String, selector: String, configJson: String): PerformancePreset
    fun update(id: String, revision: Long, name: String, selector: String, configJson: String): PerformancePreset
    fun delete(id: String): Boolean
    fun exportEnvelope(): String
    fun importEnvelope(envelope: String): List<PerformancePreset>

    object Unavailable : McpPresetStore {
        override fun list(): List<PerformancePreset> = emptyList()
        override fun get(id: String): PerformancePreset? = null
        override fun create(name: String, selector: String, configJson: String): PerformancePreset = unavailableStore()
        override fun update(id: String, revision: Long, name: String, selector: String, configJson: String): PerformancePreset = unavailableStore()
        override fun delete(id: String): Boolean = false
        override fun exportEnvelope(): String = unavailableStore()
        override fun importEnvelope(envelope: String): List<PerformancePreset> = unavailableStore()

        private fun <T> unavailableStore(): T = throw IllegalStateException("Preset store is unavailable")
    }
}

/**
 * The Android adapter uses the same repository rules and the app's v5 Room
 * table. MCP service restarts therefore cannot erase configured presets.
 */
class AndroidMcpPresetStore(context: Context) : McpPresetStore {
    private val repository = PerformancePresetRepository(
        RoomPerformancePresetStore(AppDatabase.get(context.applicationContext).performancePresetDao()),
    )

    override fun list(): List<PerformancePreset> = repository.list()

    override fun get(id: String): PerformancePreset? = repository.get(id)

    override fun create(name: String, selector: String, configJson: String): PerformancePreset = repository.create(name, selector, configJson)

    override fun update(
        id: String,
        revision: Long,
        name: String,
        selector: String,
        configJson: String,
    ): PerformancePreset = repository.update(id, revision, name, selector, configJson)

    override fun delete(id: String): Boolean = repository.delete(id)

    override fun exportEnvelope(): String = JSONObject()
        .put("format", FORMAT)
        .put("schemaVersion", SCHEMA_VERSION)
        .put("presets", JSONArray(repository.list().filterNot(PerformancePreset::isFallback).map(::toJson)))
        .toString()

    override fun importEnvelope(envelope: String): List<PerformancePreset> {
        val root = JSONObject(envelope)
        require(root.optString("format") == FORMAT && root.optInt("schemaVersion") == SCHEMA_VERSION) {
            "Preset envelope is invalid"
        }
        val values = root.optJSONArray("presets") ?: throw IllegalArgumentException("Preset envelope is invalid")
        val imports = (0 until values.length()).map { index ->
            val value = values.optJSONObject(index) ?: throw IllegalArgumentException("Preset envelope is invalid")
            io.github.xororz.localdream.data.PresetImport(
                name = value.optString("name"),
                selector = value.optString("selector"),
                configJson = value.optString("configJson"),
            )
        }
        return repository.import(imports)
    }

    private fun toJson(preset: PerformancePreset): JSONObject = JSONObject()
        .put("presetId", preset.id)
        .put("name", preset.name)
        .put("selector", preset.selector)
        .put("configJson", preset.configJson)
        .put("revision", preset.revision)

    private companion object {
        const val FORMAT = "vision-dream-performance-preset"
        const val SCHEMA_VERSION = 1
    }
}

/** Synchronous adapter used only from the MCP service worker threads. */
private class RoomPerformancePresetStore(private val dao: PerformancePresetDao) : PerformancePresetStore {
    override fun all(): List<PerformancePreset> = runBlocking { dao.list().map(::toDomain) }

    override fun get(id: String): PerformancePreset? = runBlocking { dao.getById(id)?.let(::toDomain) }

    override fun getByName(name: String): PerformancePreset? = runBlocking { dao.getByName(name)?.let(::toDomain) }

    override fun save(preset: PerformancePreset) {
        val now = System.currentTimeMillis()
        runBlocking {
            val current = dao.getById(preset.id)
            val entity = PerformancePresetEntity(
                id = preset.id,
                name = preset.name,
                selector = preset.selector,
                configJson = preset.configJson,
                revision = preset.revision,
                isFallback = preset.isFallback,
                createdAt = current?.createdAt ?: now,
                updatedAt = now,
            )
            if (current == null) dao.insert(entity) else dao.update(entity)
        }
    }

    override fun delete(id: String): Boolean = runBlocking { dao.deleteUserPreset(id) > 0 }

    private fun toDomain(entity: PerformancePresetEntity): PerformancePreset = PerformancePreset(
        id = entity.id,
        name = entity.name,
        selector = entity.selector,
        configJson = entity.configJson,
        revision = entity.revision,
        isFallback = entity.isFallback,
    )
}
