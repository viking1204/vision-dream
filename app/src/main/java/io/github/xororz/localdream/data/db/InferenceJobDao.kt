package io.github.xororz.localdream.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface InferenceJobDao {
    @Transaction
    suspend fun insertAccepted(job: InferenceJobEntity, snapshot: PresetSnapshotEntity) {
        insert(job)
        insertSnapshot(snapshot)
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(job: InferenceJobEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSnapshot(snapshot: PresetSnapshotEntity)

    @Query("SELECT * FROM preset_snapshots WHERE jobId = :jobId")
    suspend fun snapshotFor(jobId: String): PresetSnapshotEntity?

    @Query("SELECT * FROM inference_jobs WHERE id = :jobId")
    suspend fun getById(jobId: String): InferenceJobEntity?

    @Query("SELECT * FROM inference_jobs WHERE ownerId = :ownerId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun listForOwner(ownerId: String, limit: Int): List<InferenceJobEntity>

    @Query("UPDATE inference_jobs SET status = :status, updatedAt = :updatedAt WHERE id = :jobId")
    suspend fun updateStatus(jobId: String, status: String, updatedAt: Long): Int

    @Transaction
    suspend fun deleteAccepted(jobId: String) {
        deleteSnapshot(jobId)
        delete(jobId)
    }

    @Query("DELETE FROM preset_snapshots WHERE jobId = :jobId")
    suspend fun deleteSnapshot(jobId: String): Int

    @Query("DELETE FROM inference_jobs WHERE id = :jobId")
    suspend fun delete(jobId: String): Int
}
