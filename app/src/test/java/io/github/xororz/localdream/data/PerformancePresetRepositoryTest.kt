package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformancePresetRepositoryTest {
    @Test
    fun updateIncrementsRevisionAndKeepsExistingSnapshotUnchanged() {
        val repository = PerformancePresetRepository(InMemoryPerformancePresetStore())
        val preset = repository.create(
            name = "Balanced",
            selector = "balanced",
            configJson = "{\"steps\":20}",
        )
        val snapshot = repository.snapshot(preset.id)

        val updated = repository.update(
            id = preset.id,
            expectedRevision = preset.revision,
            name = "Balanced v2",
            selector = "balanced",
            configJson = "{\"steps\":28}",
        )

        assertEquals(1, snapshot.revision)
        assertEquals("{\"steps\":20}", snapshot.configJson)
        assertEquals(2, updated.revision)
        assertEquals("{\"steps\":28}", updated.configJson)
    }

    @Test
    fun importRenamesConflictingUserPresetAndNeverDeletesFallback() {
        val repository = PerformancePresetRepository(InMemoryPerformancePresetStore())
        repository.create("Balanced", "balanced", "{}")

        val imported = repository.import(
            listOf(
                PresetImport(name = "Balanced", selector = "balanced", configJson = "{\"steps\":30}"),
            ),
        )

        assertEquals(1, imported.size)
        assertNotEquals("Balanced", imported.single().name)
        assertFalse(repository.delete(PerformancePresetRepository.COMPATIBILITY_FALLBACK_ID))
        assertTrue(repository.get(PerformancePresetRepository.COMPATIBILITY_FALLBACK_ID) != null)
    }
}
