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
    fun baseModelAndNsfwAreNotEmittedAsFilterTags() {
        // The filter bar must NOT carry base-model families or the NSFW marker;
        // those surface as a backend chip + content badge on the card instead.
        val sdxl = ModelTagDerivation.deriveTags(model(isSdxl = true))
        val sd15 = ModelTagDerivation.deriveTags(model(runOnCpu = true))
        val nsfw = ModelTagDerivation.deriveTags(
            model(contentRating = ModelContentRating.NSFW),
        )
        for (forbidden in listOf("SDXL", "SD1.5", "NSFW")) {
            assertFalse(forbidden in sdxl)
            assertFalse(forbidden in sd15)
            assertFalse(forbidden in nsfw)
        }
    }

    @Test
    fun keywordStyleDetection() {
        assertTrue("写实" in ModelTagDerivation.deriveTags(model(description = "A realistic photo of a cat")))
        assertTrue("人像" in ModelTagDerivation.deriveTags(model(name = "Portrait base", description = "face")))
        assertTrue("风景" in ModelTagDerivation.deriveTags(model(description = "beautiful landscape scenery")))
        assertTrue("动漫" in ModelTagDerivation.deriveTags(model(description = "二次元 anime style")))
    }

    @Test
    fun expandedStyleTagsAreDerived() {
        assertTrue("插画" in ModelTagDerivation.deriveTags(model(description = "digital illustration artwork")))
        assertTrue("赛博朋克" in ModelTagDerivation.deriveTags(model(description = "cyberpunk neon city")))
        assertTrue("国风" in ModelTagDerivation.deriveTags(model(description = "古风水墨 chinese style")))
        assertTrue("3D" in ModelTagDerivation.deriveTags(model(description = "3d render cgi")))
        assertTrue("像素" in ModelTagDerivation.deriveTags(model(description = "8bit pixel art")))
        assertTrue("水彩" in ModelTagDerivation.deriveTags(model(description = "watercolor painting")))
        assertTrue("电影感" in ModelTagDerivation.deriveTags(model(description = "cinematic film still")))
        assertTrue("机甲" in ModelTagDerivation.deriveTags(model(description = "mecha robot battle")))
        assertTrue("可爱" in ModelTagDerivation.deriveTags(model(description = "cute kawaii chibi")))
        assertTrue("暗黑" in ModelTagDerivation.deriveTags(model(description = "gothic dark fantasy horror")))
        assertTrue("建筑" in ModelTagDerivation.deriveTags(model(description = "architecture building city")))
        assertTrue("美食" in ModelTagDerivation.deriveTags(model(description = "food cuisine料理")))
        assertTrue("动物" in ModelTagDerivation.deriveTags(model(description = "animal pet cat")))
        assertTrue("时尚" in ModelTagDerivation.deriveTags(model(description = "fashion outfit clothing")))
    }

    @Test
    fun tagsAreDeduplicated() {
        // isAnima no longer yields "动漫" (base-model tags are gone); a keyword
        // match must still not duplicate the same style token.
        val tags = ModelTagDerivation.deriveTags(model(description = "anime art 动漫"))
        assertEquals(1, tags.count { it == "动漫" })
    }

    @Test
    fun collectTagsExcludesBaseModelAndNsfwAndOrdersByPreference() {
        val models = listOf(
            model(name = "a", description = "realistic portrait"),
            model(name = "b", isSdxl = true, description = "anime illustration"),
            model(name = "c", isAnima = true, contentRating = ModelContentRating.NSFW, description = "landscape scenery"),
        )
        val all = ModelTagDerivation.collectTags(models)
        // Base-model / content markers must be absent from the filter bar.
        assertFalse("SDXL" in all)
        assertFalse("SD1.5" in all)
        assertFalse("NSFW" in all)
        // Style tags must be present.
        assertTrue("写实" in all)
        assertTrue("人像" in all)
        assertTrue("动漫" in all)
        assertTrue("插画" in all)
        assertTrue("风景" in all)
        // Stable preferred ordering: core styles precede themes.
        assertTrue(all.indexOf("动漫") < all.indexOf("插画"))
        assertTrue(all.indexOf("写实") < all.indexOf("风景"))
    }
}
