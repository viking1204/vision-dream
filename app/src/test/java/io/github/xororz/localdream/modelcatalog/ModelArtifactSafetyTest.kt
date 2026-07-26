package io.github.xororz.localdream.modelcatalog

import io.github.xororz.localdream.data.ModelFileLayouts
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelArtifactSafetyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `catalog artifact uses declared size or global hard limit`() {
        assertEquals(
            1234L,
            CatalogArtifactDownloadLimits.maximumBytes(expectedSizeBytes = 1234L),
        )
        assertEquals(
            CatalogArtifactDownloadLimits.MAX_DOWNLOAD_BYTES,
            CatalogArtifactDownloadLimits.maximumBytes(expectedSizeBytes = null),
        )
        assertThrows(IllegalArgumentException::class.java) {
            CatalogArtifactDownloadLimits.maximumBytes(expectedSizeBytes = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CatalogArtifactDownloadLimits.maximumBytes(
                expectedSizeBytes = CatalogArtifactDownloadLimits.MAX_DOWNLOAD_BYTES + 1L,
            )
        }
    }

    @Test
    fun `directory entries count toward archive entry limit`() {
        val archive = zipBytes(
            "first/" to byteArrayOf(),
            "second/" to byteArrayOf(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                BoundedModelZipExtractor.extractFlat(
                    zipStream = ByteArrayInputStream(archive),
                    destination = temporaryFolder.newFolder("entry-limit"),
                    limits = BoundedModelZipExtractor.Limits(
                        maxEntries = 1,
                        maxExtractedBytes = 1024L,
                    ),
                )
            }
        }
    }

    @Test
    fun `directory entry payload counts toward inflated byte limit`() {
        val archive = zipBytes("payload/" to ByteArray(5) { 1 })

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                BoundedModelZipExtractor.extractFlat(
                    zipStream = ByteArrayInputStream(archive),
                    destination = temporaryFolder.newFolder("byte-limit"),
                    limits = BoundedModelZipExtractor.Limits(
                        maxEntries = 2,
                        maxExtractedBytes = 4L,
                    ),
                )
            }
        }
    }

    @Test
    fun `required zero byte file makes prepared model incomplete`() {
        val modelDirectory = temporaryFolder.newFolder("zero-required")
        ModelFileLayouts.sd15Npu.requiredFiles.forEach { name ->
            modelDirectory.resolve(name).writeBytes(byteArrayOf(1))
        }
        modelDirectory.resolve("unet.bin").writeBytes(byteArrayOf())

        assertNull(PreparedModelValidator.detectCompleteLayout(modelDirectory))

        modelDirectory.resolve("unet.bin").writeBytes(byteArrayOf(1))
        assertNotNull(PreparedModelValidator.detectCompleteLayout(modelDirectory))
    }

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, payload) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(payload)
                zip.closeEntry()
            }
        }
        bytes.toByteArray()
    }
}
