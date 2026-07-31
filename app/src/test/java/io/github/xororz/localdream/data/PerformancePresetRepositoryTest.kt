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
        val qualifications = InMemoryPerformancePresetQualificationStore()
        val repository = PerformancePresetRepository(InMemoryPerformancePresetStore(), qualifications)
        val defaultPreset = repository.create("Default", "default", validConfig)
        val modelPreset = repository.create("Model", "model", validConfig)
        val defaultContext = qualificationContext(defaultPreset, "model-b")
        val modelContext = qualificationContext(modelPreset, "model-a")
        qualifications.save(qualification(defaultPreset, defaultContext))
        qualifications.save(qualification(modelPreset, modelContext))
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

    @Test
    fun builtInPresetCannotBeUpdatedOrDeletedButCustomPresetRemainsMutable() {
        val store = InMemoryPerformancePresetStore()
        val repository = PerformancePresetRepository(store)
        val builtIn = PerformancePreset(
            id = "built-in-balanced",
            name = "Balanced",
            selector = "balanced",
            configJson = validConfig,
            revision = 1,
            isBuiltIn = true,
        )
        store.save(builtIn)
        val custom = repository.create("Custom", "custom", validConfig)

        try {
            repository.update(builtIn.id, 1, "Changed", "changed", validConfig)
            throw AssertionError("Expected built-in update to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: immutability is a domain rule, not a UI convention.
        }
        assertFalse(repository.delete(builtIn.id).deleted)
        assertTrue(repository.delete(custom.id).deleted)
    }

    @Test
    fun disabledOverrideUsesRecommendedPresetAndKeepsModelBindingDormant() {
        val store = InMemoryPerformancePresetStore()
        val repository = PerformancePresetRepository(store)
        val recommended = PerformancePreset(
            id = PerformancePresetRepository.RECOMMENDED_DEFAULT_PRESET_ID,
            name = "持续性能",
            selector = "sustained_performance",
            configJson = validConfig,
            revision = 1,
            isBuiltIn = true,
        )
        store.save(recommended)
        val custom = repository.create("Model custom", "model-custom", validConfig)

        assertEquals(recommended.id, repository.resolve(modelId = "model-a").presetId)
        repository.bind(PerformancePresetBinding.DEFAULT, recommended.id)
        repository.bind(PerformancePresetBinding.model("model-a"), custom.id)
        assertEquals(custom.id, repository.resolve(modelId = "model-a").presetId)

        assertTrue(repository.unbind(PerformancePresetBinding.DEFAULT))
        assertEquals(recommended.id, repository.resolve(modelId = "model-a").presetId)
        assertEquals(custom.id, repository.binding(PerformancePresetBinding.model("model-a"))?.presetId)
    }

    @Test
    fun legacyCustomPresetCanKeepANameLaterUsedByABuiltIn() {
        val store = InMemoryPerformancePresetStore()
        val repository = PerformancePresetRepository(store)
        val custom = repository.create("持续性能", "legacy-sustained", validConfig)
        store.save(
            PerformancePreset(
                id = "built-in-sustained",
                name = "持续性能",
                selector = "sustained_performance",
                configJson = validConfig,
                revision = 1,
                isBuiltIn = true,
            ),
        )

        val updated = repository.update(
            custom.id,
            custom.revision,
            custom.name,
            custom.selector,
            validConfig,
        )

        assertEquals("持续性能", updated.name)
        assertEquals(2, store.all().count { it.name == "持续性能" })
    }

    private fun qualificationContext(preset: PerformancePreset, modelId: String) = PresetQualificationContext(
        modelId = modelId,
        modelAssetSha256 = "a".repeat(64),
        runtimeFingerprint = "b".repeat(64),
        scenarioSetSha256 = "c".repeat(64),
        appBuild = "1.0",
        presetSnapshotSha256 = PerformancePresetQualification.snapshotSha256(preset),
    )

    private fun qualification(
        preset: PerformancePreset,
        context: PresetQualificationContext,
    ) = PerformancePresetQualification(
        id = "qualification-${preset.id}",
        presetId = preset.id,
        presetRevision = preset.revision,
        presetSnapshotSha256 = context.presetSnapshotSha256,
        modelId = context.modelId,
        modelAssetSha256 = context.modelAssetSha256,
        scenarioSetSha256 = context.scenarioSetSha256,
        runtimeFingerprint = context.runtimeFingerprint,
        appBuild = context.appBuild,
        qualificationLevel = PerformancePresetQualificationLevel.TARGET_VALIDATED,
        evidenceManifestSha256 = "d".repeat(64),
        createdAt = 1L,
    )
}
