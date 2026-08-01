package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPromptSamplesTest {
    @Test
    fun `installed model receives two nsfw samples and one safe sample`() {
        val model = Model(
            id = "adult_model",
            name = "Adult Model",
            description = "portrait model",
            baseUrl = "",
            isDownloaded = true,
            contentRating = ModelContentRating.NSFW,
        )

        val samples = ModelPromptSamples.forInstalledModels(listOf(model))

        assertEquals(3, samples.size)
        assertTrue(samples.all { it.seedKey.startsWith("model-sample:${model.id}:") })
        assertTrue(samples.any { "21 to 34" in it.prompt })
        assertTrue(samples.any { "1man" in it.prompt })
        assertTrue(samples.all { "adult" in it.prompt })
        assertTrue(
            samples.all { sample ->
                "woman" !in sample.prompt || "East Asian adult woman" in sample.prompt
            },
        )
        assertTrue(samples.all { it.negativePrompt.isNotBlank() })
        assertTrue(samples.all { "full-body" in it.prompt })
        assertTrue(samples.all { Regex("figures? visible").containsMatchIn(it.prompt) })
        assertTrue(samples.all { "no crop" in it.prompt })
        assertTrue(samples.all { "upper body" in it.negativePrompt })
        assertTrue(samples.all { "cut off feet" in it.negativePrompt })
        assertTrue(samples.all { it.modelId == model.id })
        assertTrue(samples.all { it.sampling.scheduler.isNotBlank() })
        assertTrue(samples.all { ":v8:" in it.seedKey })
        assertEquals(2, samples.count { it.title.contains(" · NSFW · ") })
        assertEquals(1, samples.count { it.title.contains(" · 非 NSFW · ") })

        val solo = samples[0]
        assertTrue("exactly one person" in solo.prompt)
        assertTrue("full-body composition" in solo.prompt)
        assertTrue("entire figure visible" in solo.prompt)
        assertTrue("prominent voluptuous breasts" in solo.prompt)
        assertTrue("twins" in solo.negativePrompt)
        assertTrue("duplicate woman" in solo.negativePrompt)

        val couple = samples[1]
        assertTrue("exactly two adults" in couple.prompt)
        assertTrue("1man" in couple.prompt)
        assertTrue("beautiful East Asian adult woman" in couple.prompt)
        assertTrue("intimate interaction" in couple.prompt)
        assertTrue("standing side by side" in couple.prompt)
        assertTrue("his arm around her waist" in couple.prompt)
        assertTrue("both complete figures visible" in couple.prompt)
    }

    @Test
    fun `sample catalog excludes uninstalled models and library rows stay editable`() {
        val installed = Model(
            id = "installed",
            name = "Anything",
            description = "anime",
            baseUrl = "",
            isDownloaded = true,
        )
        val absent = installed.copy(id = "absent", isDownloaded = false)

        val samples = ModelPromptSamples.forInstalledModels(listOf(installed, absent))
        val user = io.github.xororz.localdream.data.db.PromptTemplateEntity(
            id = 7,
            title = "User prompt",
            prompt = "portrait",
            negativePrompt = "blurry",
            createdAt = 1,
            updatedAt = 1,
            lastUsedAt = null,
        )
        val library = ModelPromptSamples.libraryItems(listOf(user), "")

        assertEquals(1, library.size)
        assertEquals(7L, library.single().templateId)
        assertTrue(samples.all { "adult" in it.prompt })
        assertTrue(samples.all { "East Asian adult woman" in it.prompt })
        assertEquals(2, samples.count { it.title.contains(" · NSFW · ") })
        assertTrue(library.single().sampling == null)
    }

    @Test
    fun `anime samples use dynamic and safe style-specific schedulers`() {
        val model = Model(
            id = "anime_model",
            name = "Anime Model",
            description = "anime illustration",
            baseUrl = "",
            isDownloaded = true,
            isSdxl = true,
        )

        val samples = ModelPromptSamples.samplesFor(model)

        assertEquals(
            PromptSamplingParameters(26, 6f, "euler_a"),
            samples.first().sampling,
        )
        assertEquals(
            PromptSamplingParameters(26, 6.5f, "euler_a_karras"),
            samples.last().sampling,
        )
        assertEquals(
            PromptSamplingParameters(28, 5.5f, "euler_a_karras"),
            samples[1].sampling,
        )
    }

    @Test
    fun `localized model description participates in style sampling`() {
        val photo = Model(
            id = "portrait",
            name = "Portrait",
            description = "风格：亚洲女性照片写实、细腻皮肤",
            baseUrl = "",
            isDownloaded = true,
            isSdxl = true,
        )

        val samples = ModelPromptSamples.samplesFor(photo)

        assertTrue(samples.all { it.prompt.startsWith("RAW photo, photorealistic") })
        assertEquals(
            PromptSamplingParameters(30, 5.5f, "dpm_sde_karras"),
            samples.first().sampling,
        )
        assertEquals(
            PromptSamplingParameters(32, 5f, "dpm_sde_karras"),
            samples[1].sampling,
        )
    }

    @Test
    fun `hybrid illustrious model uses stronger anime evidence`() {
        val model = Model(
            id = "aMixIllustrious_aMix",
            name = "A-Mix Illustrious",
            description = "风格：Illustrious 动漫、2.5D 与轻写实融合",
            baseUrl = "",
            isDownloaded = true,
            isSdxl = true,
        )

        val samples = ModelPromptSamples.samplesFor(model)

        assertTrue(samples.all { it.prompt.startsWith("masterpiece, best quality") })
        assertEquals(
            PromptSamplingParameters(26, 6f, "euler_a"),
            samples.first().sampling,
        )
    }

    @Test
    fun `miaomiao realskin uses chinese hot spring photography samples`() {
        val model = Model(
            id = "miaomiao_realskin_v1.4",
            name = "Miaomiao RealSkin 1.4",
            description = "风格：东亚女性照片写实、湿润皮肤与自然肤质",
            baseUrl = "",
            isDownloaded = true,
            isSdxl = true,
        )

        val samples = ModelPromptSamples.samplesFor(model)

        assertEquals(3, samples.size)
        assertTrue(samples.all { ":v9:" in it.seedKey })
        assertTrue(samples.all { "Chinese adult" in it.prompt })
        assertTrue(samples.all { "full-body" in it.prompt })
        assertTrue(samples.all { "natural hot spring" in it.prompt })
        assertTrue(samples.all { "glowing blue night pearls" in it.prompt })
        assertTrue("wet white sheer robe" in samples[0].prompt)
        assertTrue("visible nipples" in samples[0].prompt)
        assertTrue("exactly two Chinese adults" in samples[1].prompt)
        assertTrue("opaque layered white hanfu" in samples[2].prompt)
        assertTrue("nude" in samples[2].negativePrompt)
    }

    @Test
    fun `accelerated model config overrides visual-style recipes`() {
        val dmd2 = Model(
            id = "portrait_dmd2",
            name = "Portrait DMD2",
            description = "photorealistic",
            baseUrl = "",
            isDownloaded = true,
            isSdxl = true,
            configDefaults = ModelConfig(steps = 6f, cfg = 1f, scheduler = "lcm"),
        )
        val turbo = dmd2.copy(
            id = "portrait_turbo",
            name = "Portrait Turbo",
            configDefaults = ModelConfig(),
        )
        val dmd = dmd2.copy(
            id = "portrait_dmd",
            name = "Portrait DMD",
            configDefaults = ModelConfig(),
        )

        assertTrue(
            ModelPromptSamples.samplesFor(dmd2)
                .all { it.sampling == PromptSamplingParameters(6, 1f, "lcm") },
        )
        assertTrue(
            ModelPromptSamples.samplesFor(turbo)
                .all { it.sampling == PromptSamplingParameters(10, 1f, "euler") },
        )
        assertTrue(
            ModelPromptSamples.samplesFor(dmd)
                .all { it.sampling == PromptSamplingParameters(8, 1f, "lcm") },
        )
    }

    @Test
    fun `model-scoped picker keeps global prompts and only matching model samples`() {
        val first = Model(
            id = "first",
            name = "First",
            description = "photo",
            baseUrl = "",
            isDownloaded = true,
        )
        val second = first.copy(id = "second", name = "Second")
        val user = io.github.xororz.localdream.data.db.PromptTemplateEntity(
            id = 9,
            title = "Custom",
            prompt = "custom prompt",
            negativePrompt = "custom negative",
            createdAt = 1,
            updatedAt = 1,
            lastUsedAt = null,
        )

        val secondSamples = ModelPromptSamples.samplesFor(second).mapIndexed { index, sample ->
            io.github.xororz.localdream.data.db.PromptTemplateEntity(
                id = index + 20L,
                title = sample.title,
                prompt = sample.prompt,
                negativePrompt = sample.negativePrompt,
                createdAt = 1,
                updatedAt = 1,
                lastUsedAt = null,
                modelId = sample.modelId,
                sampleKey = sample.seedKey,
                steps = sample.sampling.steps,
                cfg = sample.sampling.cfg,
                scheduler = sample.sampling.scheduler,
            )
        }
        val firstSample = ModelPromptSamples.samplesFor(first).first().let { sample ->
            io.github.xororz.localdream.data.db.PromptTemplateEntity(
                id = 30,
                title = sample.title,
                prompt = sample.prompt,
                negativePrompt = sample.negativePrompt,
                createdAt = 1,
                updatedAt = 1,
                lastUsedAt = null,
                modelId = sample.modelId,
                sampleKey = sample.seedKey,
                steps = sample.sampling.steps,
                cfg = sample.sampling.cfg,
                scheduler = sample.sampling.scheduler,
            )
        }

        val merged = ModelPromptSamples.libraryItems(
            templates = listOf(user, firstSample) + secondSamples,
            query = "",
            modelId = second.id,
        )

        assertEquals(4, merged.size)
        assertEquals(3, merged.count { it.modelId == second.id })
        assertEquals(1, merged.count { it.modelId == null })
        assertTrue(merged.filter { it.modelId == second.id }.all { it.sampling != null })
    }
}
