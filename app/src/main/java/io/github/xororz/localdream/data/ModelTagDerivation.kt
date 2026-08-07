package io.github.xororz.localdream.data

/**
 * Derives a compact, Chinese display tag set from a [Model] so the model list
 * page can offer tag-based filtering without a manual curation step.
 *
 * The filter tag bar exposes *style / theme* tags (动漫/写实/人像/风景/插画/
 * 赛博朋克/国风/3D/…) derived from the model name + description. Base-model
 * families (SD1.5/SDXL) and the NSFW content marker are intentionally excluded
 * from the filter bar — they surface as a dedicated backend chip and a content
 * badge on the model card instead, so they do not pollute style filtering. The
 * keyword regexes keep both Chinese and English matchers; only the emitted
 * display token is localized.
 */
object ModelTagDerivation {

    // Keyword -> style / theme tag. Multiple distinct tags may apply to one
    // model. Order here is only a fallback; [collectTags] re-sorts using
    // [PREFERRED_ORDER] so the bar stays stable across model sets.
    private val KEYWORD_RULES: List<Pair<Regex, String>> = listOf(
        // Core styles
        Regex("""动漫|二次元|anime|comic|manga""", RegexOption.IGNORE_CASE) to "动漫",
        Regex("""写实|真实|realistic|photoreal|photo|写真""", RegexOption.IGNORE_CASE) to "写实",
        Regex("""人像|人物|portrait|face|人物肖像|selfie|头像""", RegexOption.IGNORE_CASE) to "人像",
        Regex("""风景|场景|landscape|scenery|scene|cityscape""", RegexOption.IGNORE_CASE) to "风景",
        Regex("""插画|illustration|illust""", RegexOption.IGNORE_CASE) to "插画",
        Regex("""卡通|cartoon|toon""", RegexOption.IGNORE_CASE) to "卡通",
        Regex("""手绘|sketch|hand.?drawn|手描き""", RegexOption.IGNORE_CASE) to "手绘",
        Regex("""油画|oil\s?painting|厚涂""", RegexOption.IGNORE_CASE) to "油画",
        Regex("""水彩|watercolor|watercolour""", RegexOption.IGNORE_CASE) to "水彩",
        Regex("""像素|pixel|8\s?bit|像素风|dot.?art""", RegexOption.IGNORE_CASE) to "像素",
        // Themes / genres
        Regex("""赛博朋克|cyberpunk""", RegexOption.IGNORE_CASE) to "赛博朋克",
        Regex("""蒸汽朋克|steampunk""", RegexOption.IGNORE_CASE) to "蒸汽朋克",
        Regex("""国风|中国风|古风|水墨|chinese\s?style|hanfu|汉服""", RegexOption.IGNORE_CASE) to "国风",
        Regex("""日系|japanese\s?style|日式""", RegexOption.IGNORE_CASE) to "日系",
        Regex("""韩系|korean\s?style|韩式""", RegexOption.IGNORE_CASE) to "韩系",
        Regex("""3d|三维|cgi|render""", RegexOption.IGNORE_CASE) to "3D",
        Regex("""电影感|cinematic|film\s?still|电影风""", RegexOption.IGNORE_CASE) to "电影感",
        Regex("""机甲|mecha|机器人|robot|robots|机械""", RegexOption.IGNORE_CASE) to "机甲",
        Regex("""可爱|萌|cute|kawaii|chibi|q版""", RegexOption.IGNORE_CASE) to "可爱",
        Regex("""暗黑|哥特|gothic|dark\s?fantasy|horror|恐怖|暗黑系""", RegexOption.IGNORE_CASE) to "暗黑",
        Regex("""建筑|architecture|building|城市|city""", RegexOption.IGNORE_CASE) to "建筑",
        Regex("""美食|料理|food|cuisine|食物|foodie""", RegexOption.IGNORE_CASE) to "美食",
        Regex("""动物|animal|cat|dog|宠物|pet|兽""", RegexOption.IGNORE_CASE) to "动物",
        Regex("""时尚|fashion|服饰|outfit|clothing|穿搭""", RegexOption.IGNORE_CASE) to "时尚",
        Regex("""游戏|game|rpg|游戏原画""", RegexOption.IGNORE_CASE) to "游戏",
        Regex("""奇幻|魔法|fantasy|magic|魔幻""", RegexOption.IGNORE_CASE) to "奇幻",
        Regex("""复古|怀旧|retro|vintage|old\s?school""", RegexOption.IGNORE_CASE) to "复古",
        Regex("""科幻|sci.?fi|science\s?fiction|未来""", RegexOption.IGNORE_CASE) to "科幻",
        Regex("""极简|minimalist|简洁""", RegexOption.IGNORE_CASE) to "极简",
        Regex("""霓虹|neon""", RegexOption.IGNORE_CASE) to "霓虹",
        Regex("""黑白|monochrome|noir|单色""", RegexOption.IGNORE_CASE) to "黑白",
    )

    /** Stable ordered tag list for a single model (de-duplicated). */
    fun deriveTags(model: Model): List<String> = deriveTags("${model.name} ${model.description}")

    /**
     * Same derivation for sources that are not a [Model] — upscalers, for
     * instance, carry their style only in a localized display name
     * ("动漫放大" / "写实放大"). Keeping one keyword table means the network
     * API and the in-app filter bar can never drift apart.
     */
    fun deriveTags(text: String): List<String> {
        val tags = LinkedHashSet<String>()

        // Keyword-derived style / theme tags from name + description.
        // Base-model families (SDXL / SD1.5) and the NSFW content marker are
        // intentionally NOT emitted here — they surface as a backend chip and a
        // content badge on the card, and would only add noise to style filters.
        for ((regex, tag) in KEYWORD_RULES) {
            if (regex.containsMatchIn(text)) tags += tag
        }

        return tags.toList()
    }

    // Preferred display order for the style / theme tags. [collectTags] keeps
    // this order for known tags and appends any unknown derived tag afterwards.
    private val PREFERRED_ORDER = listOf(
        "动漫", "卡通", "插画", "手绘", "油画", "水彩", "像素",
        "写实", "写真", "人像", "风景",
        "赛博朋克", "蒸汽朋克", "国风", "日系", "韩系", "3D", "电影感",
        "机甲", "可爱", "暗黑", "建筑", "美食", "动物", "时尚",
        "游戏", "奇幻", "复古", "科幻", "极简", "霓虹", "黑白",
    )

    /** All distinct tags across the given models, in a stable, UI-friendly order. */
    fun collectTags(models: List<Model>): List<String> {
        val seen = LinkedHashSet<String>()
        models.forEach { seen += deriveTags(it) }
        val ordered = PREFERRED_ORDER.filter { it in seen }.toMutableList()
        seen.filterNot { it in ordered }.forEach { ordered += it }
        return ordered
    }
}
