package io.github.xororz.localdream.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PerformancePresetQualificationDao {
    @Query("SELECT * FROM performance_preset_qualifications ORDER BY createdAt ASC, id ASC")
    suspend fun list(): List<PerformancePresetQualificationEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(qualification: PerformancePresetQualificationEntity)

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM performance_preset_qualifications
            WHERE presetId = :presetId
              AND presetRevision = :presetRevision
              AND presetSnapshotSha256 = :presetSnapshotSha256
              AND modelId = :modelId
              AND modelAssetSha256 = :modelAssetSha256
              AND runtimeFingerprint = :runtimeFingerprint
              AND scenarioSetSha256 = :scenarioSetSha256
              AND appBuild = :appBuild
              AND qualificationLevel IN ('TARGET_VALIDATED', 'FINAL_VALIDATED')
              AND revokedAt IS NULL
        )
        """,
    )
    suspend fun hasActiveTargetQualification(
        presetId: String,
        presetRevision: Long,
        presetSnapshotSha256: String,
        modelId: String,
        modelAssetSha256: String,
        runtimeFingerprint: String,
        scenarioSetSha256: String,
        appBuild: String,
    ): Boolean

    @Query("UPDATE performance_preset_qualifications SET revokedAt = :revokedAt WHERE presetId = :presetId AND revokedAt IS NULL")
    suspend fun revokeActiveForPreset(presetId: String, revokedAt: Long): Int
}
