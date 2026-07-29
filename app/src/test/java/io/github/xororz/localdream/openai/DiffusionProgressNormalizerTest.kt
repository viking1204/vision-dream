package io.github.xororz.localdream.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiffusionProgressNormalizerTest {
    @Test
    fun `progress consumption does not request costly diffusion previews`() {
        val payload = nativeGenerationPayload(
            parameters = ImageRequestParameters(
                modelId = "model",
                prompt = "fixture",
                negativePrompt = "",
                steps = 20,
                cfg = 7f,
                scheduler = "euler_a",
            ),
            width = 1024,
            height = 1024,
            requestDiffusionPreviews = false,
        )

        assertEquals(false, payload.getBoolean("show_diffusion_process"))
        assertEquals("png", payload.getString("output_format"))
    }

    @Test
    fun `preview requests stay explicit and opt in`() {
        val payload = nativeGenerationPayload(
            parameters = ImageRequestParameters(
                modelId = "model",
                prompt = "fixture",
                negativePrompt = "",
                steps = 20,
                cfg = 7f,
                scheduler = "euler_a",
            ),
            width = 1024,
            height = 1024,
            requestDiffusionPreviews = true,
        )

        assertEquals(true, payload.getBoolean("show_diffusion_process"))
    }

    @Test
    fun `filters pipeline stages and emits all diffusion steps`() {
        val normalizer = DiffusionProgressNormalizer(expectedSteps = 3)

        assertNull(normalizer.accept(rawStep = 1, rawTotalSteps = 5))
        assertEquals(1 to 3, normalizer.accept(rawStep = 1, rawTotalSteps = 5))
        assertEquals(2 to 3, normalizer.accept(rawStep = 2, rawTotalSteps = 5))
        assertEquals(3 to 3, normalizer.accept(rawStep = 3, rawTotalSteps = 5))
        assertNull(normalizer.accept(rawStep = 5, rawTotalSteps = 5))
    }

    @Test
    fun `preserves a backend that already reports diffusion-only progress`() {
        val normalizer = DiffusionProgressNormalizer(expectedSteps = 2)

        assertEquals(1 to 2, normalizer.accept(rawStep = 1, rawTotalSteps = 2))
        assertNull(normalizer.accept(rawStep = 1, rawTotalSteps = 2))
        assertEquals(2 to 2, normalizer.accept(rawStep = 2, rawTotalSteps = 2))
    }

    @Test
    fun `normalizes a multi-stage prefix that ends at another raw step`() {
        val normalizer = DiffusionProgressNormalizer(expectedSteps = 2)

        assertNull(normalizer.accept(rawStep = 1, rawTotalSteps = 5))
        assertNull(normalizer.accept(rawStep = 2, rawTotalSteps = 5))
        assertEquals(1 to 2, normalizer.accept(rawStep = 2, rawTotalSteps = 5))
        assertEquals(2 to 2, normalizer.accept(rawStep = 3, rawTotalSteps = 5))
    }
}
