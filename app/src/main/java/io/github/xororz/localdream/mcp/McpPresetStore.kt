package io.github.xororz.localdream.mcp

import android.content.Context
import androidx.room.withTransaction
import io.github.xororz.localdream.BuildConfig
import io.github.xororz.localdream.data.PerformancePreset
import io.github.xororz.localdream.data.PerformancePresetBinding
import io.github.xororz.localdream.data.PerformancePresetQualification
import io.github.xororz.localdream.data.PerformancePresetQualificationLevel
import io.github.xororz.localdream.data.PerformancePresetQualificationStore
import io.github.xororz.localdream.data.PerformancePresetRepository
import io.github.xororz.localdream.data.PerformancePresetStore
import io.github.xororz.localdream.data.PresetDeleteResult
import io.github.xororz.localdream.data.PresetQualificationContext
import io.github.xororz.localdream.data.PresetSnapshot
import io.github.xororz.localdream.data.RuntimeCompatibilityEvaluator
import io.github.xororz.localdream.data.RuntimeProbe
import io.github.xororz.localdream.data.RuntimeProbeStatus
import io.github.xororz.localdream.data.db.AppDatabase
import io.github.xororz.localdream.data.db.PerformancePresetBindingEntity
import io.github.xororz.localdream.data.db.PerformancePresetDao
import io.github.xororz.localdream.data.db.PerformancePresetEntity
import io.github.xororz.localdream.data.db.PerformancePresetQualificationEntity
import io.github.xororz.localdream.service.BackendService
import java.io.File
import java.security.MessageDigest
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
    fun delete(id: String): PresetDeleteResult
    fun binding(bindingKey: String): PerformancePresetBinding?
    fun bind(bindingKey: String, presetId: String): PerformancePresetBinding
    fun exportEnvelope(): String
    fun importEnvelope(envelope: String): List<PerformancePreset>

    object Unavailable : McpPresetStore {
        override fun list(): List<PerformancePreset> = emptyList()
        override fun get(id: String): PerformancePreset? = null
        override fun create(name: String, selector: String, configJson: String): PerformancePreset = unavailableStore()
        override fun update(id: String, revision: Long, name: String, selector: String, configJson: String): PerformancePreset = unavailableStore()
        override fun delete(id: String): PresetDeleteResult = PresetDeleteResult(deleted = false)
        override fun binding(bindingKey: String): PerformancePresetBinding? = null
        override fun bind(bindingKey: String, presetId: String): PerformancePresetBinding = unavailableStore()
        override fun exportEnvelope(): String = unavailableStore()
        override fun importEnvelope(envelope: String): List<PerformancePreset> = unavailableStore()

        private fun <T> unavailableStore(): T = throw IllegalStateException("Preset store is unavailable")
    }
}

/**
 * The Android adapter uses the same repository rules and the app's v6 Room
 * table. MCP service restarts therefore cannot erase configured presets.
 */
class AndroidMcpPresetStore(context: Context) : McpPresetStore {
    private val applicationContext = context.applicationContext
    private val database = AppDatabase.get(applicationContext)
    private val qualificationStore = RoomPerformancePresetQualificationStore(database)
    private val repository = PerformancePresetRepository(RoomPerformancePresetStore(database), qualificationStore)
    private val qualificationContexts = AndroidPresetQualificationContexts(applicationContext, qualificationStore)

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

    override fun delete(id: String): PresetDeleteResult = repository.delete(id)

    override fun binding(bindingKey: String): PerformancePresetBinding? = repository.binding(bindingKey)

    override fun bind(bindingKey: String, presetId: String): PerformancePresetBinding = repository.bind(
        bindingKey,
        presetId,
        qualificationContexts.forBinding(repository.get(presetId) ?: error("Preset not found"), bindingKey),
    )

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

/**
 * Resolves the same binding priority used by MCP at the moment a local UI
 * request is accepted. The returned value is a value object, so later preset
 * edits cannot change the pending launch configuration.
 */
class AndroidPerformancePresetResolver(context: Context) {
    private val applicationContext = context.applicationContext
    private val database = AppDatabase.get(applicationContext)
    private val qualificationStore = RoomPerformancePresetQualificationStore(database)
    private val repository = PerformancePresetRepository(RoomPerformancePresetStore(database), qualificationStore)
    private val qualificationContexts = AndroidPresetQualificationContexts(applicationContext, qualificationStore)

    fun resolve(modelId: String): PresetSnapshot {
        val binding = repository.binding(PerformancePresetBinding.model(modelId))
            ?: repository.binding(PerformancePresetBinding.DEFAULT)
        val boundPreset = binding?.let { repository.get(it.presetId) }
        val context = boundPreset?.let { qualificationContexts.forModel(it, modelId) }
        return repository.resolve(modelId = modelId, qualificationContext = context)
    }
}

/**
 * Converts only the live app-owned runtime facts into a binding context. The
 * imported candidate supplies the scenario-set digest, but it can never supply
 * the current model digest, runtime fingerprint or APK version by assertion.
 */
private class AndroidPresetQualificationContexts(
    private val context: Context,
    private val qualifications: PerformancePresetQualificationStore,
) {
    fun forBinding(preset: PerformancePreset, bindingKey: String): PresetQualificationContext? {
        val modelId = bindingKey.removePrefix("MODEL:").takeIf { bindingKey.startsWith("MODEL:") }
        // A DEFAULT binding may cover multiple validated models. Binding it is
        // allowed once any live, exact qualification exists; every later
        // execution still resolves the context again for its requested model.
        return matchingContexts(preset, modelId).firstOrNull()
    }

    fun forModel(preset: PerformancePreset, modelId: String): PresetQualificationContext? = matchingContexts(preset, modelId).singleOrNull()

    private fun matchingContexts(preset: PerformancePreset, modelId: String?): List<PresetQualificationContext> = qualifications.all().asSequence()
        .filter(PerformancePresetQualification::isActive)
        .filter { it.presetId == preset.id && it.presetRevision == preset.revision }
        .filter { modelId == null || it.modelId == modelId }
        .map { qualification -> currentContext(qualification.modelId, qualification.scenarioSetSha256, preset) }
        .filterNotNull()
        .distinct()
        .toList()

    private fun currentContext(
        modelId: String,
        scenarioSetSha256: String,
        preset: PerformancePreset,
    ): PresetQualificationContext? {
        val probe = BackendService.runtimeProbe.value
        if (probe.status != RuntimeProbeStatus.VERIFIED) return null
        val modelDigest = File(File(io.github.xororz.localdream.data.Model.getModelsDir(context), modelId), "unet.bin")
            .takeIf(File::isFile)
            ?.let(RuntimeCompatibilityEvaluator::sha256)
            ?: return null
        return PresetQualificationContext(
            modelId = modelId,
            modelAssetSha256 = modelDigest,
            runtimeFingerprint = probe.qualificationFingerprint(),
            scenarioSetSha256 = scenarioSetSha256,
            appBuild = BuildConfig.VERSION_NAME,
            presetSnapshotSha256 = PerformancePresetQualification.snapshotSha256(preset),
        )
    }
}

/** Mirrors Python's sorted `json.dumps(probe_as_dict(probe))` qualification contract. */
private fun RuntimeProbe.qualificationFingerprint(): String {
    val libraries = loadedLibraryFingerprints.toSortedMap().entries.joinToString(", ") { (name, digest) ->
        "${JSONObject.quote(name)}: ${JSONObject.quote(digest)}"
    }
    val reasons = rejectionReasons.joinToString(", ") { JSONObject.quote(it) }
    val payload = buildString {
        append("{\"abi\": ").append(jsonString(abi))
        append(", \"context_fingerprint\": ").append(jsonString(contextFingerprint))
        append(", \"device_model\": ").append(jsonString(deviceModel))
        append(", \"htp_target\": ").append(jsonString(htpTarget))
        append(", \"loaded_library_fingerprints\": {").append(libraries).append("}")
        append(", \"native_ready\": ").append(nativeReady?.toString() ?: "null")
        append(", \"qairt_version\": ").append(jsonString(qairtVersion))
        append(", \"rejection_reasons\": [").append(reasons).append("]")
        append(", \"soc\": ").append(jsonString(soc))
        append(", \"status\": ").append(JSONObject.quote(status.name)).append("}")
    }
    return MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

private fun jsonString(value: String?): String = value?.let(JSONObject::quote) ?: "null"

/** Synchronous adapter used only from the MCP service worker threads. */
private class RoomPerformancePresetStore(private val database: AppDatabase) : PerformancePresetStore {
    private val dao: PerformancePresetDao
        get() = database.performancePresetDao()
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
                isBuiltIn = preset.isBuiltIn,
                createdAt = current?.createdAt ?: now,
                updatedAt = now,
            )
            if (current == null) dao.insert(entity) else dao.update(entity)
        }
    }

    override fun binding(bindingKey: String): PerformancePresetBinding? = runBlocking {
        database.performancePresetBindingDao().get(bindingKey)?.let(::toDomain)
    }

    override fun bindingsForPreset(presetId: String): List<PerformancePresetBinding> = runBlocking {
        database.performancePresetBindingDao().listForPreset(presetId).map(::toDomain)
    }

    override fun saveBinding(binding: PerformancePresetBinding) {
        runBlocking {
            database.performancePresetBindingDao().save(
                PerformancePresetBindingEntity(binding.bindingKey, binding.presetId, binding.updatedAt),
            )
        }
    }

    override fun deleteUserPresetAndRebind(id: String, fallbackId: String): PresetDeleteResult = runBlocking {
        database.withTransaction {
            val rebound = database.performancePresetBindingDao().listForPreset(id).map { it.bindingKey }
            database.performancePresetQualificationDao().revokeActiveForPreset(id, System.currentTimeMillis())
            val deleted = dao.deleteUserPreset(id) > 0
            if (!deleted) return@withTransaction PresetDeleteResult(deleted = false)
            if (rebound.isNotEmpty()) {
                database.performancePresetBindingDao().rebindPreset(id, fallbackId, System.currentTimeMillis())
            }
            PresetDeleteResult(deleted = true, reboundBindingKeys = rebound)
        }
    }

    private fun toDomain(entity: PerformancePresetEntity): PerformancePreset = PerformancePreset(
        id = entity.id,
        name = entity.name,
        selector = entity.selector,
        configJson = entity.configJson,
        revision = entity.revision,
        isFallback = entity.isFallback,
        isBuiltIn = entity.isBuiltIn,
    )

    private fun toDomain(entity: PerformancePresetBindingEntity): PerformancePresetBinding = PerformancePresetBinding(
        bindingKey = entity.bindingKey,
        presetId = entity.presetId,
        updatedAt = entity.updatedAt,
    )
}

/** Room adapter keeps revoked qualification evidence for audit without exposing write APIs to MCP. */
private class RoomPerformancePresetQualificationStore(
    private val database: AppDatabase,
) : PerformancePresetQualificationStore {
    override fun all(): List<PerformancePresetQualification> = runBlocking {
        database.performancePresetQualificationDao().list().map(::toDomain)
    }

    override fun save(qualification: PerformancePresetQualification) {
        runBlocking {
            database.performancePresetQualificationDao().insert(
                PerformancePresetQualificationEntity(
                    id = qualification.id,
                    presetId = qualification.presetId,
                    presetRevision = qualification.presetRevision,
                    presetSnapshotSha256 = qualification.presetSnapshotSha256,
                    modelId = qualification.modelId,
                    modelAssetSha256 = qualification.modelAssetSha256,
                    scenarioSetSha256 = qualification.scenarioSetSha256,
                    runtimeFingerprint = qualification.runtimeFingerprint,
                    appBuild = qualification.appBuild,
                    qualificationLevel = qualification.qualificationLevel.name,
                    evidenceManifestSha256 = qualification.evidenceManifestSha256,
                    createdAt = qualification.createdAt,
                    revokedAt = qualification.revokedAt,
                ),
            )
        }
    }

    fun saveAllAtomically(qualifications: List<PerformancePresetQualification>) {
        runBlocking {
            database.withTransaction {
                qualifications.forEach { qualification ->
                    database.performancePresetQualificationDao().insert(qualification.toEntity())
                }
            }
        }
    }

    override fun revokeForPreset(presetId: String, revokedAt: Long) {
        runBlocking { database.performancePresetQualificationDao().revokeActiveForPreset(presetId, revokedAt) }
    }

    private fun PerformancePresetQualification.toEntity(): PerformancePresetQualificationEntity = PerformancePresetQualificationEntity(
        id = id,
        presetId = presetId,
        presetRevision = presetRevision,
        presetSnapshotSha256 = presetSnapshotSha256,
        modelId = modelId,
        modelAssetSha256 = modelAssetSha256,
        scenarioSetSha256 = scenarioSetSha256,
        runtimeFingerprint = runtimeFingerprint,
        appBuild = appBuild,
        qualificationLevel = qualificationLevel.name,
        evidenceManifestSha256 = evidenceManifestSha256,
        createdAt = createdAt,
        revokedAt = revokedAt,
    )

    private fun toDomain(entity: PerformancePresetQualificationEntity): PerformancePresetQualification = PerformancePresetQualification(
        id = entity.id,
        presetId = entity.presetId,
        presetRevision = entity.presetRevision,
        presetSnapshotSha256 = entity.presetSnapshotSha256,
        modelId = entity.modelId,
        modelAssetSha256 = entity.modelAssetSha256,
        scenarioSetSha256 = entity.scenarioSetSha256,
        runtimeFingerprint = entity.runtimeFingerprint,
        appBuild = entity.appBuild,
        qualificationLevel = PerformancePresetQualificationLevel.valueOf(entity.qualificationLevel),
        evidenceManifestSha256 = entity.evidenceManifestSha256,
        createdAt = entity.createdAt,
        revokedAt = entity.revokedAt,
    )
}
