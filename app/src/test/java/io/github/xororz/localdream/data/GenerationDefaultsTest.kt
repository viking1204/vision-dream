package io.github.xororz.localdream.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationDefaultsTest {
    @Test
    fun globalNegativePromptIsPresentAndStyleNeutral() {
        val prompt = GenerationDefaults.GLOBAL.negativePrompt.lowercase()

        assertTrue(prompt.isNotBlank())
        assertTrue("bad anatomy" in prompt)
        assertTrue("watermark" in prompt)
        assertFalse("anime" in prompt)
        assertFalse("photo" in prompt)
        assertFalse("nsfw" in prompt)
    }

    @Test
    fun missingNegativePromptUsesDefaultButExplicitEmptyIsPreserved() {
        assertTrue(GenerationDefaults.resolveNegativePrompt(null).isNotBlank())
        assertTrue(GenerationDefaults.resolveNegativePrompt("").isEmpty())
    }
}
