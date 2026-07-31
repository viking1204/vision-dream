package io.github.xororz.localdream.data

import androidx.room.withTransaction
import io.github.xororz.localdream.data.db.AppDatabase
import io.github.xororz.localdream.data.db.InferenceJobEntity
import io.github.xororz.localdream.data.db.PresetSnapshotEntity
import java.util.UUID

enum class InferenceJobStatus(val wireValue: String) {
    QUEUED("queued"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(value: String?): InferenceJobStatus = entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

data class InferenceJob(
    val id: String,
    val ownerId: String,
    val presetId: String,
    val status: InferenceJobStatus,
)

data class InferenceJobSnapshot(
    val jobId: String,
    val presetId: String,
    val name: String,
    val selector: String,
    val configJson: String,
    val revision: Long,
)

interface InferenceJobStore {
    fun save(job: InferenceJob, snapshot: InferenceJobSnapshot)
    fun snapshotFor(jobId: String): InferenceJobSnapshot?
}

/**
 * 在受理请求时写入不可更新的预设快照；之后预设的编辑不会改变已受理 Job 的执行参数。
 */
class InferenceJobRepository(
    private val store: InferenceJobStore,
    private val presets: PerformancePresetRepository,
) {
    fun accept(ownerId: String, presetId: String): InferenceJob {
        require(ownerId.isNotBlank()) { "Job owner is required" }
        val snapshot = presets.snapshot(presetId)
        val job = InferenceJob(
            id = UUID.randomUUID().toString(),
            ownerId = ownerId,
            presetId = snapshot.presetId,
            status = InferenceJobStatus.QUEUED,
        )
        store.save(
            job,
            InferenceJobSnapshot(
                jobId = job.id,
                presetId = snapshot.presetId,
                name = snapshot.name,
                selector = snapshot.selector,
                configJson = snapshot.configJson,
                revision = snapshot.revision,
            ),
        )
        return job
    }

    fun snapshotFor(jobId: String): InferenceJobSnapshot? = store.snapshotFor(jobId)
}

/**
 * Room-backed accepted-request recorder. The Job row and its preset snapshot are
 * persisted in one transaction, so a later preset edit cannot affect an already
 * accepted request.
 */
class RoomInferenceJobRepository(
    private val database: AppDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun accept(
        ownerId: String,
        modelId: String? = null,
        explicitPresetId: String? = null,
        qualificationContext: PresetQualificationContext? = null,
    ): InferenceJobSnapshot {
        require(ownerId.isNotBlank()) { "Job owner is required" }
        return database.withTransaction {
            val bindingDao = database.performancePresetBindingDao()
            val explicitId = explicitPresetId?.takeIf(String::isNotBlank)
            val automaticBinding = if (explicitId == null) {
                modelId?.let { model ->
                    bindingDao.get(PerformancePresetBinding.model(model))?.presetId
                } ?: bindingDao.get(PerformancePresetBinding.DEFAULT)?.presetId
            } else {
                null
            }
            val presetId = explicitId
                ?: automaticBinding
                ?: PerformancePresetRepository.COMPATIBILITY_FALLBACK_ID
            val preset = requireNotNull(database.performancePresetDao().getById(presetId)) {
                "Preset not found"
            }
            val parsedConfig = PerformancePresetConfig.parse(preset.configJson)
            parsedConfig.requireExecutableSnapshot(preset.isFallback)
            if (automaticBinding != null && !preset.isFallback) {
                val context = qualificationContext ?: throw PresetNotTargetValidatedException()
                val qualified = database.performancePresetQualificationDao().hasActiveTargetQualification(
                    presetId = preset.id,
                    presetRevision = preset.revision,
                    presetSnapshotSha256 = context.presetSnapshotSha256,
                    modelId = context.modelId,
                    modelAssetSha256 = context.modelAssetSha256,
                    runtimeFingerprint = context.runtimeFingerprint,
                    scenarioSetSha256 = context.scenarioSetSha256,
                    appBuild = context.appBuild,
                )
                if (!qualified) throw PresetNotTargetValidatedException()
            }
            val acceptedAt = nowMillis()
            val job = InferenceJobEntity(
                id = UUID.randomUUID().toString(),
                ownerId = ownerId,
                presetId = preset.id,
                status = InferenceJobStatus.QUEUED.wireValue,
                createdAt = acceptedAt,
                updatedAt = acceptedAt,
            )
            val snapshot = InferenceJobSnapshot(
                jobId = job.id,
                presetId = preset.id,
                name = preset.name,
                selector = preset.selector,
                configJson = preset.configJson,
                revision = preset.revision,
            )
            database.inferenceJobDao().insertAccepted(
                job,
                PresetSnapshotEntity(
                    jobId = snapshot.jobId,
                    presetId = snapshot.presetId,
                    name = snapshot.name,
                    selector = snapshot.selector,
                    configJson = snapshot.configJson,
                    revision = snapshot.revision,
                ),
            )
            snapshot
        }
    }

    suspend fun updateStatus(jobId: String, status: InferenceJobStatus) {
        database.inferenceJobDao().updateStatus(jobId, status.wireValue, nowMillis())
    }

    suspend fun discard(jobId: String) {
        database.inferenceJobDao().deleteAccepted(jobId)
    }
}

/** 仅供 JVM 规则测试和不接入 Room 的调用方使用。 */
class InMemoryInferenceJobStore : InferenceJobStore {
    private val snapshots = mutableMapOf<String, InferenceJobSnapshot>()

    override fun save(job: InferenceJob, snapshot: InferenceJobSnapshot) {
        snapshots[job.id] = snapshot
    }

    override fun snapshotFor(jobId: String): InferenceJobSnapshot? = snapshots[jobId]
}
