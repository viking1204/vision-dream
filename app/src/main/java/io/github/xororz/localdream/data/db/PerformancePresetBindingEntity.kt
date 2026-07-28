package io.github.xororz.localdream.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "performance_preset_bindings",
    indices = [Index(value = ["presetId"])],
)
data class PerformancePresetBindingEntity(
    @PrimaryKey val bindingKey: String,
    val presetId: String,
    val updatedAt: Long,
)
