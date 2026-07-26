package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFileLayoutTest {
    @Test
    fun detectsCompleteBackendAndAcceptsRuntimeOptionals() {
        val files = ModelFileLayouts.sd15Npu.requiredFiles + setOf(
            "vae_encoder.bin",
            "768x512.patch",
            "config.json",
        )

        assertEquals("sd15npu", ModelFileLayouts.detect(files)?.backendType)
        assertTrue(files.all(ModelFileLayouts.sd15Npu::accepts))
    }

    @Test
    fun incompleteLayoutIsRejected() {
        assertNull(
            ModelFileLayouts.detect(
                ModelFileLayouts.sd15Cpu.requiredFiles - "unet.mnn",
            ),
        )
    }
}
