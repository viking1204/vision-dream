package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class InferenceJobRepositoryTest {
    private val configOne = """{"schemaVersion":1,"engine":{"sdxlLowRam":true,"animaLowRam":false,"animaSequentialDit":false}}"""
    private val configTwo = """{"schemaVersion":1,"engine":{"sdxlLowRam":false,"animaLowRam":true,"animaSequentialDit":true}}"""

    @Test
    fun acceptedJobPersistsImmutablePresetSnapshot() {
        val presetRepository = PerformancePresetRepository(InMemoryPerformancePresetStore())
        val preset = presetRepository.create("Fast", "fast", configOne)
        val jobs = InferenceJobRepository(InMemoryInferenceJobStore(), presetRepository)

        val accepted = jobs.accept(ownerId = "openai-client", presetId = preset.id)
        presetRepository.update(
            id = preset.id,
            expectedRevision = preset.revision,
            name = "Fast updated",
            selector = "fast",
            configJson = configTwo,
        )

        val snapshot = jobs.snapshotFor(accepted.id)!!
        assertEquals(accepted.id, snapshot.jobId)
        assertEquals(1, snapshot.revision)
        assertEquals(configOne, snapshot.configJson)
        assertNotEquals(presetRepository.get(preset.id)!!.configJson, snapshot.configJson)
    }
}
