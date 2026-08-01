package io.github.xororz.localdream.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PromptTemplateDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(template: PromptTemplateEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSamples(templates: List<PromptTemplateEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSeedMarker(marker: PromptSampleSeedEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM prompt_sample_seed_models WHERE modelId = :modelId)")
    suspend fun isModelSeeded(modelId: String): Boolean

    @Transaction
    suspend fun seedModelOnce(
        modelId: String,
        templates: List<PromptTemplateEntity>,
        seededAt: Long,
    ): Boolean {
        if (isModelSeeded(modelId)) return false
        insertSamples(templates)
        insertSeedMarker(PromptSampleSeedEntity(modelId, seededAt))
        return true
    }

    @Update
    suspend fun update(template: PromptTemplateEntity): Int

    @Query("DELETE FROM prompt_templates WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT * FROM prompt_templates WHERE id = :id")
    suspend fun getById(id: Long): PromptTemplateEntity?

    @Query(
        """
        SELECT * FROM prompt_templates
        ORDER BY
            CASE WHEN lastUsedAt IS NULL THEN 1 ELSE 0 END,
            lastUsedAt DESC,
            updatedAt DESC,
            id DESC
        """,
    )
    fun observeAll(): Flow<List<PromptTemplateEntity>>

    @Query(
        """
        SELECT * FROM prompt_templates
        WHERE :query = ''
           OR INSTR(LOWER(title), LOWER(:query)) > 0
           OR INSTR(LOWER(prompt), LOWER(:query)) > 0
           OR INSTR(LOWER(negativePrompt), LOWER(:query)) > 0
        ORDER BY
            CASE WHEN lastUsedAt IS NULL THEN 1 ELSE 0 END,
            lastUsedAt DESC,
            updatedAt DESC,
            id DESC
        """,
    )
    fun observeSearch(query: String): Flow<List<PromptTemplateEntity>>

    @Query(
        """
        SELECT * FROM prompt_templates
        WHERE prompt = :prompt AND negativePrompt = :negativePrompt
        ORDER BY updatedAt DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun findExact(
        prompt: String,
        negativePrompt: String,
    ): PromptTemplateEntity?

    @Query(
        """
        UPDATE prompt_templates
        SET lastUsedAt = :usedAt,
            updatedAt = CASE WHEN updatedAt > :usedAt THEN updatedAt ELSE :usedAt END,
            useCount = useCount + 1
        WHERE id = :id
        """,
    )
    suspend fun markUsed(id: Long, usedAt: Long): Int
}
