package io.github.xororz.localdream.data

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun migrationCopiesCompleteDirectoryAndRetainsSource() {
        val source = temporaryFolder.newFolder("legacy")
        val target = temporaryFolder.newFolder("public")
        val migration = temporaryFolder.newFolder("migration")
        val model = File(source, "dream-model").apply { mkdirs() }
        val nested = File(model, "nested").apply { mkdirs() }
        File(model, "config.json").writeText("""{"backend_type":"sdxl"}""")
        File(nested, "weights.bin").writeBytes(byteArrayOf(1, 2, 3, 4))

        val report = ModelStorage.migrateLegacyEntries(source, target, migration)

        assertEquals(ModelStorage.MigrationReport(1, 0, 0), report)
        assertTrue(File(source, "dream-model/config.json").isFile)
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4),
            File(target, "dream-model/nested/weights.bin").readBytes(),
        )
        assertFalse(migration.exists())
    }

    @Test
    fun migrationNeverOverwritesExistingPublicModel() {
        val source = temporaryFolder.newFolder("legacy")
        val target = temporaryFolder.newFolder("public")
        val migration = temporaryFolder.newFolder("migration")
        File(source, "same-model").apply { mkdirs() }
        File(source, "same-model/source.bin").writeText("source")
        File(target, "same-model").apply { mkdirs() }
        val existing = File(target, "same-model/existing.bin").apply { writeText("existing") }

        val report = ModelStorage.migrateLegacyEntries(source, target, migration)

        assertEquals(ModelStorage.MigrationReport(0, 1, 0), report)
        assertEquals("existing", existing.readText())
        assertFalse(File(target, "same-model/source.bin").exists())
    }

    @Test
    fun migrationIgnoresStrayFilesAtRepositoryRoot() {
        val source = temporaryFolder.newFolder("legacy")
        val target = temporaryFolder.newFolder("public")
        val migration = File(temporaryFolder.root, "migration")
        File(source, "partial.tmp").writeText("not a model directory")

        val report = ModelStorage.migrateLegacyEntries(source, target, migration)

        assertEquals(ModelStorage.MigrationReport(0, 0, 0), report)
        assertTrue(File(source, "partial.tmp").exists())
        assertFalse(migration.exists())
    }
}
