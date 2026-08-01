package io.github.xororz.localdream.data

import io.github.xororz.localdream.data.db.PromptSampleSeedEntity
import io.github.xororz.localdream.data.db.PromptTemplateDao
import io.github.xororz.localdream.data.db.PromptTemplateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptRepositoryTest {
    @Test
    fun `create trims fields and derives a title`() = runBlocking {
        val repository = PromptRepository(FakePromptTemplateDao())

        val created = repository.create(
            title = " ",
            prompt = "  portrait, cinematic light  ",
            negativePrompt = " blurry ",
            now = 100,
        )

        assertEquals("portrait, cinematic light", created.title)
        assertEquals("portrait, cinematic light", created.prompt)
        assertEquals("blurry", created.negativePrompt)
        assertEquals(100L, created.createdAt)
        assertEquals(0, created.useCount)
    }

    @Test
    fun `update mark used and delete preserve repository semantics`() = runBlocking {
        val dao = FakePromptTemplateDao()
        val repository = PromptRepository(dao)
        val created = repository.create("Old", "prompt", now = 100)

        val updated = repository.update(
            id = created.id,
            title = "New",
            prompt = "updated prompt",
            negativePrompt = "negative",
            now = 200,
        )
        assertNotNull(updated)
        assertEquals("New", updated?.title)
        assertTrue(repository.markUsed(created.id, usedAt = 300))
        assertEquals(1, repository.get(created.id)?.useCount)
        assertEquals(300L, repository.get(created.id)?.lastUsedAt)
        assertTrue(repository.delete(created.id))
        assertNull(repository.get(created.id))
    }

    @Test
    fun `editable model samples seed once and stay deleted`() = runBlocking {
        val repository = PromptRepository(FakePromptTemplateDao())
        val model = Model(
            id = "installed",
            name = "Installed",
            description = "photo",
            baseUrl = "",
            isDownloaded = true,
        )

        assertEquals(1, repository.ensureEditableModelSamples(listOf(model), now = 100))
        assertEquals(0, repository.ensureEditableModelSamples(listOf(model), now = 200))
        val seeded = repository.observeAll().first()
        assertEquals(3, seeded.size)
        assertTrue(seeded.all { it.modelId == model.id })
        assertTrue(seeded.all { it.sampleKey?.startsWith("model-sample:${model.id}:") == true })
        assertTrue(seeded.all { it.steps != null && it.cfg != null && it.scheduler != null })

        assertTrue(repository.delete(seeded.first().id))
        assertEquals(0, repository.ensureEditableModelSamples(listOf(model), now = 300))
        assertEquals(2, repository.observeAll().first().size)
    }

    private class FakePromptTemplateDao : PromptTemplateDao {
        private val rows = linkedMapOf<Long, PromptTemplateEntity>()
        private val seedMarkers = linkedMapOf<String, PromptSampleSeedEntity>()
        private val state = MutableStateFlow<List<PromptTemplateEntity>>(emptyList())
        private var nextId = 1L

        override suspend fun insert(template: PromptTemplateEntity): Long {
            val id = nextId++
            rows[id] = template.copy(id = id)
            publish()
            return id
        }

        override suspend fun insertSamples(templates: List<PromptTemplateEntity>): List<Long> = templates.map { template ->
            val duplicate = template.sampleKey?.let { key -> rows.values.any { it.sampleKey == key } } == true
            if (duplicate) {
                -1L
            } else {
                insert(template)
            }
        }

        override suspend fun insertSeedMarker(marker: PromptSampleSeedEntity) {
            check(marker.modelId !in seedMarkers)
            seedMarkers[marker.modelId] = marker
        }

        override suspend fun isModelSeeded(modelId: String): Boolean = modelId in seedMarkers

        override suspend fun update(template: PromptTemplateEntity): Int {
            if (template.id !in rows) return 0
            rows[template.id] = template
            publish()
            return 1
        }

        override suspend fun deleteById(id: Long): Int {
            val deleted = if (rows.remove(id) != null) 1 else 0
            publish()
            return deleted
        }

        override suspend fun getById(id: Long): PromptTemplateEntity? = rows[id]

        override fun observeAll(): Flow<List<PromptTemplateEntity>> = state

        override fun observeSearch(query: String): Flow<List<PromptTemplateEntity>> = state

        override suspend fun findExact(
            prompt: String,
            negativePrompt: String,
        ): PromptTemplateEntity? = rows.values.firstOrNull {
            it.prompt == prompt && it.negativePrompt == negativePrompt
        }

        override suspend fun markUsed(id: Long, usedAt: Long): Int {
            val current = rows[id] ?: return 0
            rows[id] = current.copy(
                lastUsedAt = usedAt,
                updatedAt = maxOf(current.updatedAt, usedAt),
                useCount = current.useCount + 1,
            )
            publish()
            return 1
        }

        private fun publish() {
            state.value = rows.values.toList()
        }
    }
}
