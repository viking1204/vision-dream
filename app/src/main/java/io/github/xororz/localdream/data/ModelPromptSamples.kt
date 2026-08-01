package io.github.xororz.localdream.data

import java.util.Locale
import kotlin.math.roundToInt

data class PromptSamplingParameters(
    val steps: Int,
    val cfg: Float,
    val scheduler: String,
)

/** A mutable prompt-library row loaded from Room. */
data class PromptLibraryItem(
    val stableId: String,
    val title: String,
    val prompt: String,
    val negativePrompt: String,
    val modelId: String? = null,
    val sampling: PromptSamplingParameters? = null,
    val templateId: Long,
    val useCount: Int = 0,
)

data class PromptSampleSeed(
    val seedKey: String,
    val title: String,
    val prompt: String,
    val negativePrompt: String,
    val modelId: String,
    val sampling: PromptSamplingParameters,
)

/** Creates the initial editable, model-specific samples for installed models. */
object ModelPromptSamples {
    fun forInstalledModels(models: List<Model>): List<PromptSampleSeed> = models
        .asSequence()
        .filter { it.isDownloaded }
        .sortedBy { it.name.lowercase(Locale.ROOT) }
        .flatMap { model -> samplesFor(model).asSequence() }
        .toList()

    fun samplesFor(model: Model): List<PromptSampleSeed> {
        val style = modelStyle(model)
        val negative = model.defaults.negativePrompt.ifBlank {
            GenerationDefaults.DEFAULT_NEGATIVE_PROMPT
        }
        // Every model exposes the same three clearly labeled scenarios. This avoids
        // silently dropping adult samples merely because repository metadata omitted
        // the NSFW tag; the tag remains catalogue metadata rather than prompt policy.
        val prompts = if (model.id == MIAOMIAO_REALSKIN_MODEL_ID) {
            miaomiaoRealSkinSamples(style, negative)
        } else {
            defaultSamples(style, negative)
        }
        val sampleRevision = if (model.id == MIAOMIAO_REALSKIN_MODEL_ID) "v9" else "v8"
        return prompts.mapIndexed { index, definition ->
            PromptSampleSeed(
                seedKey = "model-sample:${model.id}:$sampleRevision:$index",
                title = "${model.name} · ${definition.label}",
                prompt = definition.prompt,
                negativePrompt = definition.negativePrompt,
                modelId = model.id,
                sampling = samplingFor(model, definition.kind),
            )
        }
    }

    private fun defaultSamples(
        style: String,
        negative: String,
    ): List<SampleDefinition> = listOf(
        SampleDefinition(
            "NSFW · 东亚单人女性",
            "$style, 1girl, solo, exactly one person, full-body composition, head-to-toe, entire figure visible, no crop, beautiful East Asian adult woman aged 21 to 34, standing, attractive symmetrical face, almond eyes, full lips, long glossy black hair, prominent voluptuous breasts, narrow waist, hourglass body, curvy hips, long shapely legs, revealing lingerie, sensual pose, warm bedroom lighting",
            "$negative, child, minor, teenager, multiple people, 2girls, group, crowd, twins, duplicate woman, extra person, man, close-up, headshot, bust shot, cowboy shot, upper body, cropped body, out of frame, cut off legs, cut off feet, hidden feet, extra limbs",
            SampleKind.NSFW_SOLO,
        ),
        SampleDefinition(
            "NSFW · 东亚男女",
            "$style, 1man, 1woman, exactly two adults, full-body full-length wide shot, both complete figures visible, no crop, standing side by side, beautiful East Asian adult woman aged 21 to 34, intimate interaction with an adult man, his arm around her waist, holding hands, affectionate gaze, woman's attractive face, long glossy black hair, prominent voluptuous breasts, narrow waist, hourglass body, curvy hips, long shapely legs, revealing lingerie, man's open shirt, warm bedroom lighting",
            "$negative, child, minor, teenager, solo, extra people, three people, group, crowd, multiple couples, 2girls, 2boys, duplicate person, close-up, headshot, bust shot, cowboy shot, upper body, cropped bodies, out of frame, cut off legs, cut off feet, hidden feet, merged bodies, fused limbs",
            SampleKind.NSFW_COUPLE,
        ),
        SampleDefinition(
            "非 NSFW · 东亚人物",
            "$style, 1girl, solo, exactly one person, full-body environmental composition, head-to-toe, entire figure visible, no crop, beautiful East Asian adult woman detective aged 21 to 34, walking, attractive expressive face, glossy black hair, athletic figure, fully clothed tailored outfit, long legs, rainy neon night market, cinematic atmosphere",
            "$negative, child, minor, teenager, empty scene, multiple people, 2girls, group, crowd, twins, duplicate woman, extra person, man, nude, nsfw, close-up, headshot, bust shot, cowboy shot, upper body, cropped body, out of frame, cut off legs, cut off feet, hidden feet",
            SampleKind.SAFE_CHARACTER,
        ),
    )

    /**
     * RealSkin is strongest at East Asian skin, wet fabric and photographic
     * lighting. Keep the decisive subject and framing tokens before scenery so
     * they survive the text encoder's finite context instead of copying a long
     * prose prompt whose final traits may be truncated.
     */
    private fun miaomiaoRealSkinSamples(
        style: String,
        negative: String,
    ): List<SampleDefinition> = listOf(
        SampleDefinition(
            "NSFW · 东亚单人女性",
            "$style, 1girl, solo, beautiful Chinese adult woman aged 25, full-body seated composition, entire figure visible head-to-bare-feet, no crop, curvy body, prominent large breasts, round hips, narrow waist, fair realistic skin, long wet loose black hair, detailed dark eyes and face, sitting on the stone edge of a natural hot spring, legs in clear water, one knee slightly raised, looking at viewer, curious smile, head tilted, wet white sheer robe clinging to her body, visible nipples and body contours, glistening bare legs, dark cave, glowing blue night pearls, mossy rocks, modern Chinese aesthetic, atmospheric photography",
            "$negative, child, minor, teenager, multiple people, man, close-up, headshot, upper body, cropped body, out of frame, cut off legs, cut off feet, opaque fabric, dry hair, bad anatomy, extra limbs, fused fingers, deformed feet",
            SampleKind.NSFW_SOLO,
        ),
        SampleDefinition(
            "NSFW · 东亚男女",
            "$style, 1man, 1woman, exactly two Chinese adults aged 25 to 34, full-body full-length composition, both complete figures visible, no crop, beautiful Chinese woman with a detailed face, long wet black hair, prominent large breasts, round hips, narrow waist and curvy body, wet white sheer robe revealing nipples and body contours, standing together in a shallow natural hot spring, adult man embracing her waist, intimate affectionate gaze, wet skin glistening, dark cave, glowing blue night pearls, mossy rocks, modern Chinese aesthetic, atmospheric photography",
            "$negative, child, minor, teenager, solo, extra people, three people, group, 2girls, 2boys, close-up, headshot, upper body, cropped bodies, out of frame, cut off legs, cut off feet, merged bodies, fused limbs, bad anatomy, extra limbs",
            SampleKind.NSFW_COUPLE,
        ),
        SampleDefinition(
            "非 NSFW · 东亚人物",
            "$style, 1girl, solo, beautiful Chinese adult woman aged 25, full-body composition, entire figure visible head-to-toe, no crop, detailed dark eyes and face, long wet loose black hair, fair realistic skin, elegant curvy figure, opaque layered white hanfu, barefoot, standing beside a natural hot spring, dark cave, glowing blue night pearls, mossy rocks, traditional Chinese motifs, refined ink-inspired colors, atmospheric lighting, professional photography",
            "$negative, child, minor, teenager, multiple people, man, nude, nipples, nsfw, sheer fabric, transparent clothes, close-up, headshot, upper body, cropped body, out of frame, cut off legs, cut off feet, bad anatomy, extra limbs",
            SampleKind.SAFE_CHARACTER,
        ),
    )

    fun libraryItems(
        templates: List<io.github.xororz.localdream.data.db.PromptTemplateEntity>,
        query: String,
        modelId: String? = null,
    ): List<PromptLibraryItem> {
        val normalized = query.trim().lowercase(Locale.ROOT)
        return templates.map { template ->
            PromptLibraryItem(
                stableId = "prompt:${template.id}",
                title = template.title,
                prompt = template.prompt,
                negativePrompt = template.negativePrompt,
                modelId = template.modelId,
                sampling = template.samplingParameters(),
                templateId = template.id,
                useCount = template.useCount,
            )
        }
            .filter { item -> modelId == null || item.modelId == null || item.modelId == modelId }
            .filter { item ->
                normalized.isEmpty() || listOf(item.title, item.prompt, item.negativePrompt)
                    .any { it.lowercase(Locale.ROOT).contains(normalized) }
            }
    }

    private fun io.github.xororz.localdream.data.db.PromptTemplateEntity.samplingParameters(): PromptSamplingParameters? {
        val storedSteps = steps ?: return null
        val storedCfg = cfg ?: return null
        val storedScheduler = scheduler ?: return null
        return PromptSamplingParameters(storedSteps, storedCfg, storedScheduler)
    }

    private fun samplingFor(model: Model, kind: SampleKind): PromptSamplingParameters {
        val fingerprint = "${model.id} ${model.name} ${model.description}".lowercase(Locale.ROOT)
        val explicit = model.codeDefaults.withFallback(model.configDefaults)
        val accelerated = when {
            explicit.scheduler == "lcm" || DISTILLED_TOKEN.containsMatchIn(fingerprint) ||
                "lcm" in fingerprint ->
                PromptSamplingParameters(steps = 8, cfg = 1f, scheduler = "lcm")

            "turbo" in fingerprint || "lightning" in fingerprint ->
                PromptSamplingParameters(steps = 10, cfg = 1f, scheduler = "euler")

            else -> null
        }
        val recipe = accelerated ?: styleSampling(model, fingerprint, kind)
        return PromptSamplingParameters(
            steps = explicit.steps?.roundToInt() ?: recipe.steps,
            cfg = explicit.cfg ?: recipe.cfg,
            scheduler = explicit.scheduler ?: recipe.scheduler,
        )
    }

    private fun styleSampling(
        model: Model,
        fingerprint: String,
        kind: SampleKind,
    ): PromptSamplingParameters = when (modelFamily(model, fingerprint)) {
        // Couple compositions contain two faces, four hands and overlapping limbs,
        // so non-distilled models receive more steps and slightly lower CFG than
        // the corresponding solo recipe. The lower CFG reduces fused anatomy.
        ModelFamily.PHOTO -> when (kind) {
            SampleKind.NSFW_SOLO -> PromptSamplingParameters(30, 5.5f, "dpm_sde_karras")
            SampleKind.NSFW_COUPLE -> PromptSamplingParameters(32, 5f, "dpm_sde_karras")
            SampleKind.SAFE_CHARACTER -> PromptSamplingParameters(28, 6f, "dpm_karras")
        }

        ModelFamily.ANIME -> when (kind) {
            SampleKind.NSFW_SOLO -> PromptSamplingParameters(26, 6f, "euler_a")
            SampleKind.NSFW_COUPLE -> PromptSamplingParameters(28, 5.5f, "euler_a_karras")
            SampleKind.SAFE_CHARACTER -> PromptSamplingParameters(26, 6.5f, "euler_a_karras")
        }

        ModelFamily.SDXL -> when (kind) {
            SampleKind.NSFW_SOLO -> PromptSamplingParameters(30, 6f, "dpm_sde_karras")
            SampleKind.NSFW_COUPLE -> PromptSamplingParameters(32, 5.5f, "dpm_sde_karras")
            SampleKind.SAFE_CHARACTER -> PromptSamplingParameters(28, 6.5f, "dpm_karras")
        }

        ModelFamily.GENERAL -> when (kind) {
            SampleKind.NSFW_SOLO -> PromptSamplingParameters(26, 6.5f, "dpm_sde_karras")
            SampleKind.NSFW_COUPLE -> PromptSamplingParameters(28, 6f, "dpm_sde_karras")
            SampleKind.SAFE_CHARACTER -> PromptSamplingParameters(24, 7f, "dpm_karras")
        }
    }

    private fun modelStyle(model: Model): String {
        val fingerprint = "${model.name} ${model.description}".lowercase(Locale.ROOT)
        return when (modelFamily(model, fingerprint)) {
            ModelFamily.PHOTO -> "RAW photo, photorealistic, high-detail professional photography, 50mm full-length fashion editorial"
            ModelFamily.ANIME -> "masterpiece, best quality, very aesthetic, detailed anime illustration"
            ModelFamily.SDXL -> "masterpiece, best quality, high-detail SDXL illustration"
            ModelFamily.GENERAL -> "best quality, highly detailed, ${model.name} visual style"
        }
    }

    private fun modelFamily(model: Model, fingerprint: String): ModelFamily {
        val photoScore = PHOTO_TOKENS.count(fingerprint::contains)
        val animeScore = ANIME_TOKENS.count(fingerprint::contains)
        return when {
            photoScore > animeScore -> ModelFamily.PHOTO
            animeScore > photoScore -> ModelFamily.ANIME
            model.isSdxl -> ModelFamily.SDXL
            else -> ModelFamily.GENERAL
        }
    }

    private data class SampleDefinition(
        val label: String,
        val prompt: String,
        val negativePrompt: String,
        val kind: SampleKind,
    )

    private enum class SampleKind {
        NSFW_SOLO,
        NSFW_COUPLE,
        SAFE_CHARACTER,
    }

    private enum class ModelFamily {
        PHOTO,
        ANIME,
        SDXL,
        GENERAL,
    }

    private val DISTILLED_TOKEN = Regex("(^|[_\\s-])dmd2?($|[_\\s-])")
    private const val MIAOMIAO_REALSKIN_MODEL_ID = "miaomiao_realskin_v1.4"
    private val PHOTO_TOKENS = listOf(
        "realistic",
        "reality",
        "chillout",
        "photo",
        "juggernaut",
        "写实",
        "摄影",
        "照片",
        "真人",
        "写真",
    )
    private val ANIME_TOKENS = listOf(
        "anime",
        "anything",
        "yuki",
        "qtea",
        "illustr",
        "illustrious",
        "pony",
        "动漫",
        "二次元",
        "日系",
        "插画",
        "卡通",
        "赛璐璐",
    )
}
