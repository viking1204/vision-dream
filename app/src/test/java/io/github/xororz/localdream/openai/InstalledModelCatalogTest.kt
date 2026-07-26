package io.github.xororz.localdream.openai

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstalledModelCatalogTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun imageInputCapabilityDependsOnlyOnNonEmptyBackendEncoder() {
        val directory = temporaryFolder.newFolder("model")
        val encoder = File(directory, "vae_encoder.bin")

        assertFalse(InstalledModelCatalog.supportsImageInput(directory, "sd15npu"))
        encoder.writeBytes(byteArrayOf(1))
        assertTrue(InstalledModelCatalog.supportsImageInput(directory, "sd15npu"))
        assertFalse(InstalledModelCatalog.supportsImageInput(directory, "sd15cpu"))
    }

    @Test
    fun unknownBackendNeverAdvertisesImageInput() {
        val directory = temporaryFolder.newFolder("unknown").apply {
            File(this, "vae_encoder.bin").writeBytes(byteArrayOf(1))
        }

        assertFalse(InstalledModelCatalog.supportsImageInput(directory, "unknown"))
    }
}
