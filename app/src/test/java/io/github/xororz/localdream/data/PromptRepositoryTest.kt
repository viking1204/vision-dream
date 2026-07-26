package io.github.xororz.localdream.data

import io.github.xororz.localdream.data.db.PromptTemplateDao
import io.github.xororz.localdream.data.db.PromptTemplateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    private class FakePromptTemplateDao : PromptTemplateDao {
        private val rows = linkedMapOf<Long, PromptTemplateEntity>()
        private val state = MutableStateFlow<List<PromptTemplateEntity>>(emptyList())
        private var nextId = 1L

        override suspend fun insert(template: PromptTemplateEntity): Long {
            val id = nextId++
            rows[id] = template.copy(id = id)
            publish()
            return id
        }

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
