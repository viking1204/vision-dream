package io.github.xororz.localdream.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "performance_presets")
data class PerformancePresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val selector: String,
    val configJson: String,
    val revision: Long,
    val isFallback: Boolean,
    /** Product-shipped presets are selectable but must remain immutable. */
    val isBuiltIn: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)
