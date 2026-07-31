package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformancePresetQualificationTest {
    private val config = """{"schemaVersion":1,"engine":{"sdxlLowRam":true,"animaLowRam":false,"animaSequentialDit":false}}"""
    private val digest = "a".repeat(64)

    @Test
    fun automaticBindingRequiresExactActiveTargetQualificationButExplicitSelectionIsExploratory() {
        val qualifications = InMemoryPerformancePresetQualificationStore()
        val repository = PerformancePresetRepository(InMemoryPerformancePresetStore(), qualifications)
        val preset = repository.create("Target", "target", config)
        val context = qualificationContext(preset)

        assertFalse(repository.isAutomaticBindingQualified(preset, context))
        try {
            repository.bind(PerformancePresetBinding.DEFAULT, preset.id, context)
            throw AssertionError("Expected PRESET_NOT_TARGET_VALIDATED")
        } catch (error: PresetNotTargetValidatedException) {
            assertEquals("PRESET_NOT_TARGET_VALIDATED", error.message)
        }
        assertEquals(preset.id, repository.resolve(explicitPresetId = preset.id).presetId)

        qualifications.save(qualification(preset, context, PerformancePresetQualificationLevel.TARGET_VALIDATED))

        repository.bind(PerformancePresetBinding.DEFAULT, preset.id, context)
        assertEquals(preset.id, repository.resolve(qualificationContext = context).presetId)
    }

    @Test
    fun editingOrRevokingPresetInvalidatesQualification() {
        val qualifications = InMemoryPerformancePresetQualificationStore()
        val repository = PerformancePresetRepository(InMemoryPerformancePresetStore(), qualifications)
        val preset = repository.create("Target", "target", config)
        val context = qualificationContext(preset)
        qualifications.save(qualification(preset, context, PerformancePresetQualificationLevel.FINAL_VALIDATED))
        assertTrue(repository.isAutomaticBindingQualified(preset, context))

        val updated = repository.update(preset.id, preset.revision, "Target v2", "target", config)

        assertFalse(repository.isAutomaticBindingQualified(updated, qualificationContext(updated)))
        assertFalse(qualifications.all().single().isActive)
    }

    @Test
    fun qualificationIdentityIncludesModelRuntimeScenarioAndBuild() {
        val store = InMemoryPerformancePresetQualificationStore()
        val repository = PerformancePresetRepository(InMemoryPerformancePresetStore(), store)
        val preset = repository.create("Target", "target", config)
        val context = qualificationContext(preset)
        store.save(qualification(preset, context, PerformancePresetQualificationLevel.TARGET_VALIDATED))

        assertTrue(store.hasActiveTargetQualification(preset, context))
        assertFalse(store.hasActiveTargetQualification(preset, context.copy(appBuild = "2.0")))
        assertFalse(store.hasActiveTargetQualification(preset, context.copy(runtimeFingerprint = "e".repeat(64))))
        assertFalse(store.hasActiveTargetQualification(preset, context.copy(scenarioSetSha256 = "f".repeat(64))))
    }

    private fun qualificationContext(preset: PerformancePreset) = PresetQualificationContext(
        modelId = "nova-asian-xl",
        modelAssetSha256 = digest,
        runtimeFingerprint = "b".repeat(64),
        scenarioSetSha256 = "c".repeat(64),
        appBuild = "1.0",
        presetSnapshotSha256 = PerformancePresetQualification.snapshotSha256(preset),
    )

    private fun qualification(
        preset: PerformancePreset,
        context: PresetQualificationContext,
        level: PerformancePresetQualificationLevel,
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
        qualificationLevel = level,
        evidenceManifestSha256 = "d".repeat(64),
        createdAt = 1L,
    )
}
