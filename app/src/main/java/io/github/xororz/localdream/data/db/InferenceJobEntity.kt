package io.github.xororz.localdream.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "inference_jobs",
    indices = [Index(value = ["ownerId", "createdAt"]), Index(value = ["status", "updatedAt"])],
)
data class InferenceJobEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val presetId: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "preset_snapshots")
data class PresetSnapshotEntity(
    @PrimaryKey val jobId: String,
    val presetId: String,
    val name: String,
    val selector: String,
    val configJson: String,
    val revision: Long,
)
