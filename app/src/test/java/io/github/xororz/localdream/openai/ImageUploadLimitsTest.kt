package io.github.xororz.localdream.openai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageUploadLimitsTest {
    private val limits = ImageUploadLimits(maxEdge = 8_192, maxPixels = 16_777_216L)

    @Test
    fun `accepts dimensions within edge and pixel limits`() {
        assertTrue(limits.accepts(4_096, 4_096))
    }

    @Test
    fun `rejects non-positive dimensions`() {
        assertFalse(limits.accepts(0, 512))
        assertFalse(limits.accepts(512, -1))
    }

    @Test
    fun `rejects dimensions beyond maximum edge`() {
        assertFalse(limits.accepts(8_193, 1))
    }

    @Test
    fun `rejects dimensions beyond maximum pixel count`() {
        assertFalse(limits.accepts(8_192, 2_049))
    }
}
