package io.github.xororz.localdream.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "performance_preset_qualifications",
    foreignKeys = [
        ForeignKey(
            entity = PerformancePresetEntity::class,
            parentColumns = ["id"],
            childColumns = ["presetId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["presetId", "revokedAt"]),
        Index(value = ["modelId", "modelAssetSha256", "runtimeFingerprint", "scenarioSetSha256"]),
    ],
)
data class PerformancePresetQualificationEntity(
    @PrimaryKey val id: String,
    val presetId: String,
    val presetRevision: Long,
    val presetSnapshotSha256: String,
    val modelId: String,
    val modelAssetSha256: String,
    val scenarioSetSha256: String,
    val runtimeFingerprint: String,
    val appBuild: String,
    val qualificationLevel: String,
    val evidenceManifestSha256: String,
    val createdAt: Long,
    val revokedAt: Long? = null,
)
