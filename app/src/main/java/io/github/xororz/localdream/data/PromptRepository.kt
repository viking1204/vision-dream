package io.github.xororz.localdream.data

import android.content.Context
import io.github.xororz.localdream.data.db.AppDatabase
import io.github.xororz.localdream.data.db.PromptTemplateDao
import io.github.xororz.localdream.data.db.PromptTemplateEntity
import kotlinx.coroutines.flow.Flow

/**
 * CRUD facade for reusable positive and negative prompt pairs.
 */
class PromptRepository internal constructor(
    private val dao: PromptTemplateDao,
) {
    constructor(context: Context) : this(
        AppDatabase.get(context.applicationContext).promptTemplateDao(),
    )

    fun observeAll(): Flow<List<PromptTemplateEntity>> = dao.observeAll()

    fun observeSearch(query: String): Flow<List<PromptTemplateEntity>> = dao.observeSearch(query.trim())

    suspend fun get(id: Long): PromptTemplateEntity? = dao.getById(id)

    suspend fun findExact(
        prompt: String,
        negativePrompt: String,
    ): PromptTemplateEntity? = dao.findExact(
        prompt = prompt.trim(),
        negativePrompt = negativePrompt.trim(),
    )

    /**
     * Creates each installed model's editable examples exactly once. The DAO
     * stores a separate model marker transactionally, so deleting an example
     * is a durable user choice rather than an invitation to recreate it.
     */
    suspend fun ensureEditableModelSamples(
        models: List<Model>,
        now: Long = System.currentTimeMillis(),
    ): Int {
        var seededCount = 0
        models.filter { it.isDownloaded }.forEach { model ->
            val templates = ModelPromptSamples.samplesFor(model).map { sample ->
                PromptTemplateEntity(
                    title = sample.title,
                    prompt = sample.prompt,
                    negativePrompt = sample.negativePrompt,
                    createdAt = now,
                    updatedAt = now,
                    lastUsedAt = null,
                    modelId = sample.modelId,
                    sampleKey = sample.seedKey,
                    steps = sample.sampling.steps,
                    cfg = sample.sampling.cfg,
                    scheduler = sample.sampling.scheduler,
                )
            }
            if (dao.seedModelOnce(model.id, templates, now)) {
                seededCount += 1
            }
        }
        return seededCount
    }

    suspend fun create(
        title: String,
        prompt: String,
        negativePrompt: String = "",
        now: Long = System.currentTimeMillis(),
    ): PromptTemplateEntity {
        val normalizedPrompt = prompt.trim()
        require(normalizedPrompt.isNotEmpty()) { "prompt must not be blank" }
        val normalizedTitle = title.trim().ifEmpty {
            normalizedPrompt.lineSequence().first().take(DEFAULT_TITLE_LENGTH)
        }
        val template = PromptTemplateEntity(
            title = normalizedTitle,
            prompt = normalizedPrompt,
            negativePrompt = negativePrompt.trim(),
            createdAt = now,
            updatedAt = now,
            lastUsedAt = null,
        )
        return template.copy(id = dao.insert(template))
    }

    suspend fun update(
        id: Long,
        title: String,
        prompt: String,
        negativePrompt: String,
        now: Long = System.currentTimeMillis(),
    ): PromptTemplateEntity? {
        val current = dao.getById(id) ?: return null
        val normalizedPrompt = prompt.trim()
        require(normalizedPrompt.isNotEmpty()) { "prompt must not be blank" }
        val updated = current.copy(
            title = title.trim().ifEmpty {
                normalizedPrompt.lineSequence().first().take(DEFAULT_TITLE_LENGTH)
            },
            prompt = normalizedPrompt,
            negativePrompt = negativePrompt.trim(),
            updatedAt = maxOf(current.updatedAt, now),
        )
        return if (dao.update(updated) > 0) updated else null
    }

    suspend fun delete(id: Long): Boolean = dao.deleteById(id) > 0

    suspend fun markUsed(
        id: Long,
        usedAt: Long = System.currentTimeMillis(),
    ): Boolean = dao.markUsed(id, usedAt) > 0

    companion object {
        private const val DEFAULT_TITLE_LENGTH = 48
    }
}
