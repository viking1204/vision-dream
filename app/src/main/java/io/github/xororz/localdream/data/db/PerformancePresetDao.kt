package io.github.xororz.localdream.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PerformancePresetDao {
    @Query("SELECT * FROM performance_presets ORDER BY createdAt ASC, id ASC")
    suspend fun list(): List<PerformancePresetEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(preset: PerformancePresetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(preset: PerformancePresetEntity)

    @Query("SELECT * FROM performance_presets WHERE id = :id")
    suspend fun getById(id: String): PerformancePresetEntity?

    @Query("SELECT * FROM performance_presets WHERE name = :name")
    suspend fun getByName(name: String): PerformancePresetEntity?

    @Query("DELETE FROM performance_presets WHERE id = :id AND isBuiltIn = 0")
    suspend fun deleteUserPreset(id: String): Int
}
