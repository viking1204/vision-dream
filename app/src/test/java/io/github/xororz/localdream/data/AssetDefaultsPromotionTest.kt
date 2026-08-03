package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetDefaultsPromotionTest {

    private fun current() = GenerationPrefs(
        hasSaved = false,
        prompt = "old prompt",
        negativePrompt = "old negative",
        steps = 20f,
        cfg = 7f,
        seed = "424242",
        width = 512,
        height = 512,
        scheduler = "dpm",
    )

    private fun candidate(
        prompt: String = "new prompt",
        negativePrompt: String = "new negative",
        steps: Int = 30,
        cfg: Float = 9.5f,
        width: Int = 768,
        height: Int = 1024,
        scheduler: String = "euler_a",
    ) = AssetDefaultsCandidate(
        prompt = prompt,
        negativePrompt = negativePrompt,
        steps = steps,
        cfg = cfg,
        width = width,
        height = height,
        scheduler = scheduler,
    )

    @Test
    fun `promotes every valid field`() {
        val result = AssetDefaultsPromotion.promote(current(), candidate())

        assertEquals("new prompt", result.prompt)
        assertEquals("new negative", result.negativePrompt)
        assertEquals(30f, result.steps, 0f)
        assertEquals(9.5f, result.cfg, 0f)
        assertEquals(768, result.width)
        assertEquals(1024, result.height)
        assertEquals("euler_a", result.scheduler)
    }

    @Test
    fun `marks the model as having saved defaults`() {
        assertTrue(AssetDefaultsPromotion.promote(current(), candidate()).hasSaved)
    }

    @Test
    fun `never promotes the seed`() {
        // A promoted seed would pin every future run to the same picture.
        val result = AssetDefaultsPromotion.promote(current(), candidate())

        assertEquals("424242", result.seed)
    }

    @Test
    fun `keeps the current prompt when the asset has none`() {
        val result = AssetDefaultsPromotion.promote(current(), candidate(prompt = "   "))

        assertEquals("old prompt", result.prompt)
    }

    @Test
    fun `honours a deliberately empty negative prompt`() {
        val result = AssetDefaultsPromotion.promote(current(), candidate(negativePrompt = ""))

        assertEquals("", result.negativePrompt)
    }

    @Test
    fun `rejects out of range steps`() {
        val tooMany = AssetDefaultsPromotion.promote(current(), candidate(steps = 500))
        val tooFew = AssetDefaultsPromotion.promote(current(), candidate(steps = 0))

        assertEquals(20f, tooMany.steps, 0f)
        assertEquals(20f, tooFew.steps, 0f)
    }

    @Test
    fun `rejects out of range cfg`() {
        val result = AssetDefaultsPromotion.promote(current(), candidate(cfg = 99f))

        assertEquals(7f, result.cfg, 0f)
    }

    @Test
    fun `rejects a non finite cfg`() {
        val result = AssetDefaultsPromotion.promote(current(), candidate(cfg = Float.NaN))

        assertEquals(7f, result.cfg, 0f)
    }

    @Test
    fun `rejects dimensions that are not a multiple of 64`() {
        val result = AssetDefaultsPromotion.promote(current(), candidate(width = 700, height = 700))

        assertEquals(512, result.width)
        assertEquals(512, result.height)
    }

    @Test
    fun `rejects dimensions outside the supported range`() {
        val result = AssetDefaultsPromotion.promote(
            current(),
            candidate(width = 64, height = 4096),
        )

        assertEquals(512, result.width)
        assertEquals(512, result.height)
    }

    @Test
    fun `treats size as all or nothing`() {
        // A valid width next to a rejected height would silently change the
        // aspect ratio, so neither is promoted.
        val result = AssetDefaultsPromotion.promote(current(), candidate(width = 768, height = 700))

        assertEquals(512, result.width)
        assertEquals(512, result.height)
    }

    @Test
    fun `rejects an unknown scheduler`() {
        val result = AssetDefaultsPromotion.promote(current(), candidate(scheduler = "ddim"))

        assertEquals("dpm", result.scheduler)
    }

    @Test
    fun `leaves unrelated defaults untouched`() {
        val start = current().copy(
            denoiseStrength = 0.42f,
            batchCounts = 4,
            useOpenCL = true,
            aspectRatio = "16:9",
        )

        val result = AssetDefaultsPromotion.promote(start, candidate())

        assertEquals(0.42f, result.denoiseStrength, 0f)
        assertEquals(4, result.batchCounts)
        assertEquals(true, result.useOpenCL)
        assertEquals("16:9", result.aspectRatio)
    }
}
