package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class InferenceJobRepositoryTest {
    @Test
    fun acceptedJobPersistsImmutablePresetSnapshot() {
        val presetRepository = PerformancePresetRepository(InMemoryPerformancePresetStore())
        val preset = presetRepository.create("Fast", "fast", "{\"steps\":8}")
        val jobs = InferenceJobRepository(InMemoryInferenceJobStore(), presetRepository)

        val accepted = jobs.accept(ownerId = "openai-client", presetId = preset.id)
        presetRepository.update(
            id = preset.id,
            expectedRevision = preset.revision,
            name = "Fast updated",
            selector = "fast",
            configJson = "{\"steps\":12}",
        )

        val snapshot = jobs.snapshotFor(accepted.id)!!
        assertEquals(accepted.id, snapshot.jobId)
        assertEquals(1, snapshot.revision)
        assertEquals("{\"steps\":8}", snapshot.configJson)
        assertNotEquals(presetRepository.get(preset.id)!!.configJson, snapshot.configJson)
    }
}
