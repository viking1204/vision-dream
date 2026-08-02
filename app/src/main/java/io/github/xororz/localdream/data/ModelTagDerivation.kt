package io.github.xororz.localdream.data

/**
 * Derives a compact, language-neutral tag set from a [Model] so the model list
 * page can offer tag-based filtering without a manual curation step.
 *
 * Tags are intentionally short, language-neutral tokens (mirroring the existing
 * hard-coded "CPU"/"NPU" badges) to avoid a four-language string burden while
 * still being self-explanatory to users.
 */
object ModelTagDerivation {

    // Keyword -> tag. Multiple distinct tags may apply to one model.
    private val KEYWORD_RULES: List<Pair<Regex, String>> = listOf(
        // Style
        Regex("""动漫|二次元|anime|comic|manga""", RegexOption.IGNORE_CASE) to "Anime",
        Regex("""写实|真实|realistic|photoreal|photo""", RegexOption.IGNORE_CASE) to "Realistic",
        Regex("""人像|人物|portrait|face|人物肖像""", RegexOption.IGNORE_CASE) to "Portrait",
        Regex("""风景|场景|landscape|scenery|scene""", RegexOption.IGNORE_CASE) to "Landscape",
        // Base model
        Regex("""sd\s?1\.5|sd15|1\.5""", RegexOption.IGNORE_CASE) to "SD1.5",
    )

    /** Stable ordered tag list for a single model (de-duplicated, CPU/NPU first). */
    fun deriveTags(model: Model): List<String> {
        val tags = LinkedHashSet<String>()

        // Backend / hardware
        tags += if (model.runOnCpu) "CPU" else "NPU"

        // Base model family (structural flags win over keyword scan)
        when {
            model.isSdxl -> tags += "SDXL"
            model.isAnima -> tags += "Anime"
        }

        // Content rating
        if (model.isNsfw) tags += "NSFW"

        // Keyword-derived style / base-model tags from name + description
        val haystack = "${model.name} ${model.description}"
        for ((regex, tag) in KEYWORD_RULES) {
            if (regex.containsMatchIn(haystack)) tags += tag
        }

        return tags.toList()
    }

    /** All distinct tags across the given models, in a stable, UI-friendly order. */
    fun collectTags(models: List<Model>): List<String> {
        val seen = LinkedHashSet<String>()
        models.forEach { seen += deriveTags(it) }
        // Preferred display order: hardware, base model, style, content.
        val preferred = listOf(
            "NPU", "CPU", "SDXL", "SD1.5", "Anime", "Realistic",
            "Portrait", "Landscape", "NSFW",
        )
        val ordered = preferred.filter { it in seen }.toMutableList()
        seen.filterNot { it in ordered }.forEach { ordered += it }
        return ordered
    }
}
