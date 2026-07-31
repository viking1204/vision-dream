package io.github.xororz.localdream.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PerformancePresetBindingDao {
    @Query("SELECT * FROM performance_preset_bindings WHERE bindingKey = :bindingKey")
    suspend fun get(bindingKey: String): PerformancePresetBindingEntity?

    @Query("SELECT * FROM performance_preset_bindings WHERE presetId = :presetId ORDER BY bindingKey ASC")
    suspend fun listForPreset(presetId: String): List<PerformancePresetBindingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(binding: PerformancePresetBindingEntity)

    @Query("DELETE FROM performance_preset_bindings WHERE bindingKey = :bindingKey")
    suspend fun delete(bindingKey: String): Int

    @Query("UPDATE performance_preset_bindings SET presetId = :fallbackId, updatedAt = :updatedAt WHERE presetId = :presetId")
    suspend fun rebindPreset(presetId: String, fallbackId: String, updatedAt: Long): Int
}
