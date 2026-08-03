package io.github.xororz.localdream.data

import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.data.ModelContentRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTagDerivationTest {

    private fun model(
        name: String = "test",
        description: String = "",
        runOnCpu: Boolean = false,
        isSdxl: Boolean = false,
        isAnima: Boolean = false,
        contentRating: ModelContentRating = ModelContentRating.UNKNOWN,
    ): Model = Model(
        id = "id-$name",
        name = name,
        description = description,
        baseUrl = "https://example.com",
        runOnCpu = runOnCpu,
        isSdxl = isSdxl,
        isAnima = isAnima,
        contentRating = contentRating,
    )

    @Test
    fun cpuModelDoesNotGetCpuTag_npuModelDoesNotGetNpuTag() {
        assertFalse("CPU" in ModelTagDerivation.deriveTags(model(runOnCpu = true)))
        assertFalse("NPU" in ModelTagDerivation.deriveTags(model(runOnCpu = false)))
    }

    @Test
    fun sd15BackendProducesSd15Tag() {
        // runOnCpu -> backendType "sd15cpu"; NPU fallback -> "sd15npu".
        assertTrue("SD1.5" in ModelTagDerivation.deriveTags(model(runOnCpu = true)))
        assertTrue("SD1.5" in ModelTagDerivation.deriveTags(model(runOnCpu = false)))
    }

    @Test
    fun sdxlFlagProducesSdxlTag() {
        assertTrue("SDXL" in ModelTagDerivation.deriveTags(model(isSdxl = true)))
        assertFalse("SDXL" in ModelTagDerivation.deriveTags(model(isSdxl = false)))
    }

    @Test
    fun animaFlagProducesAnimeTag() {
        assertTrue("动漫" in ModelTagDerivation.deriveTags(model(isAnima = true)))
    }

    @Test
    fun nsfwRatingProducesNsfwTag() {
        assertTrue(
            "NSFW" in ModelTagDerivation.deriveTags(
                model(contentRating = ModelContentRating.NSFW),
            ),
        )
        assertFalse(
            "NSFW" in ModelTagDerivation.deriveTags(
                model(contentRating = ModelContentRating.UNKNOWN),
            ),
        )
    }

    @Test
    fun keywordStyleDetection() {
        assertTrue("写实" in ModelTagDerivation.deriveTags(model(description = "A realistic photo of a cat")))
        assertTrue("人像" in ModelTagDerivation.deriveTags(model(name = "Portrait base", description = "face")))
        assertTrue("风景" in ModelTagDerivation.deriveTags(model(description = "beautiful landscape scenery")))
        assertTrue("动漫" in ModelTagDerivation.deriveTags(model(description = "二次元 anime style")))
    }

    @Test
    fun sd15KeywordProducesSd15Tag() {
        assertTrue("SD1.5" in ModelTagDerivation.deriveTags(model(description = "sd1.5 fine-tune")))
    }

    @Test
    fun tagsAreDeduplicated() {
        // isAnima already yields "动漫"; description "anime" must not duplicate it.
        val tags = ModelTagDerivation.deriveTags(model(isAnima = true, description = "anime art"))
        assertEquals(1, tags.count { it == "动漫" })
    }

    @Test
    fun collectTagsOrdersByPreferenceAndIncludesAll() {
        val models = listOf(
            model(name = "a", description = "realistic", runOnCpu = true),
            model(name = "b", isSdxl = true),
            model(name = "c", isAnima = true, contentRating = ModelContentRating.NSFW),
        )
        val all = ModelTagDerivation.collectTags(models)
        assertTrue("SD1.5" in all)
        assertTrue("SDXL" in all)
        assertTrue("动漫" in all)
        assertTrue("NSFW" in all)
        assertTrue("写实" in all)
        // Base-model tags (SDXL) must precede style/content tags (动漫, NSFW) in preferred order.
        assertTrue(all.indexOf("SDXL") < all.indexOf("动漫"))
        assertTrue(all.indexOf("动漫") < all.indexOf("NSFW"))
    }
}
