package io.github.xororz.localdream.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "performance_presets",
    indices = [Index(value = ["name"], unique = true)],
)
data class PerformancePresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val selector: String,
    val configJson: String,
    val revision: Long,
    val isFallback: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)
