package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformancePresetRepositoryTest {
    private val validConfig = """{"schemaVersion":1,"engine":{"sdxlLowRam":true,"animaLowRam":false,"animaSequentialDit":false}}"""

    @Test
    fun updateIncrementsRevisionAndKeepsExistingSnapshotUnchanged() {
        val repository = PerformancePresetRepository(InMemoryPerformancePresetStore())
        val preset = repository.create(
            name = "Balanced",
            selector = "balanced",
            configJson = validConfig,
        )
        val snapshot = repository.snapshot(preset.id)

        val updated = repository.update(
            id = preset.id,
            expectedRevision = preset.revision,
            name = "Balanced v2",
            selector = "balanced",
            configJson = validConfig.replace("false}}", "true}}"),
        )

        assertEquals(1, snapshot.revision)
        assertEquals(validConfig, snapshot.configJson)
        assertEquals(2, updated.revision)
        assertEquals(validConfig.replace("false}}", "true}}"), updated.configJson)
    }

    @Test
    fun importRenamesConflictingUserPresetAndNeverDeletesFallback() {
        val repository = PerformancePresetRepository(InMemoryPerformancePresetStore())
        repository.create("Balanced", "balanced", validConfig)

        val imported = repository.import(
            listOf(
                PresetImport(name = "Balanced", selector = "balanced", configJson = validConfig),
            ),
        )

        assertEquals(1, imported.size)
        assertNotEquals("Balanced", imported.single().name)
        assertFalse(repository.delete(PerformancePresetRepository.COMPATIBILITY_FALLBACK_ID).deleted)
        assertTrue(repository.get(PerformancePresetRepository.COMPATIBILITY_FALLBACK_ID) != null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun createRejectsLegacyAndNonSchemaConfigs() {
        PerformancePresetRepository(InMemoryPerformancePresetStore()).create("Balanced", "balanced", "{}")
    }

    @Test
    fun bindingResolutionUsesModelThenDefaultAndDeleteRebindsAtomically() {
        val repository = PerformancePresetRepository(InMemoryPerformancePresetStore())
        val defaultPreset = repository.create("Default", "default", validConfig)
        val modelPreset = repository.create("Model", "model", validConfig)
        repository.bind(PerformancePresetBinding.DEFAULT, defaultPreset.id)
        repository.bind(PerformancePresetBinding.model("model-a"), modelPreset.id)

        assertEquals(modelPreset.id, repository.resolve(modelId = "model-a").presetId)
        assertEquals(defaultPreset.id, repository.resolve(modelId = "model-b").presetId)

        val delete = repository.delete(modelPreset.id)

        assertTrue(delete.deleted)
        assertEquals(listOf(PerformancePresetBinding.model("model-a")), delete.reboundBindingKeys)
        assertEquals(PerformancePresetRepository.COMPATIBILITY_FALLBACK_ID, repository.resolve(modelId = "model-a").presetId)
        assertEquals(defaultPreset.id, repository.resolve(modelId = "model-b").presetId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun bindingRejectsCompatibilityFallbackAndInvalidBindingKeys() {
        val repository = PerformancePresetRepository(InMemoryPerformancePresetStore())
        repository.bind("MODEL:", PerformancePresetRepository.COMPATIBILITY_FALLBACK_ID)
    }
}
