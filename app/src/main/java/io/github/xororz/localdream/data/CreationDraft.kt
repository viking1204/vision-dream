package io.github.xororz.localdream.data

import org.json.JSONObject

/**
 * Snapshot of the chat creation screen that survives process recreation.
 *
 * Only lightweight UI state is persisted: the prompt pair, the chosen model,
 * the active generation mode, and the advanced parameters. Selected source
 * images for image-to-image / inpaint are intentionally excluded — they are
 * large and re-selected by the user when the mode needs them.
 */
data class CreationDraft(
    val prompt: String = "",
    val negativePrompt: String = "",
    val modelId: String? = null,
    val mode: String = "TXT2IMG",
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 20,
    val cfg: Float = 7f,
    val seed: String = "",
    val scheduler: String = "dpm",
) {
    fun toJson(): String = JSONObject().apply {
        put(KEY_PROMPT, prompt)
        put(KEY_NEGATIVE_PROMPT, negativePrompt)
        modelId?.let { put(KEY_MODEL_ID, it) }
        put(KEY_MODE, mode)
        put(KEY_WIDTH, width)
        put(KEY_HEIGHT, height)
        put(KEY_STEPS, steps)
        put(KEY_CFG, cfg)
        put(KEY_SEED, seed)
        put(KEY_SCHEDULER, scheduler)
    }.toString()

    companion object {
        private const val KEY_PROMPT = "prompt"
        private const val KEY_NEGATIVE_PROMPT = "negativePrompt"
        private const val KEY_MODEL_ID = "modelId"
        private const val KEY_MODE = "mode"
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val KEY_STEPS = "steps"
        private const val KEY_CFG = "cfg"
        private const val KEY_SEED = "seed"
        private const val KEY_SCHEDULER = "scheduler"

        fun fromJson(raw: String): CreationDraft? = runCatching {
            val json = JSONObject(raw)
            CreationDraft(
                prompt = json.optString(KEY_PROMPT, ""),
                negativePrompt = json.optString(KEY_NEGATIVE_PROMPT, ""),
                modelId = json.optString(KEY_MODEL_ID, "").takeIf { it.isNotEmpty() },
                mode = json.optString(KEY_MODE, "TXT2IMG"),
                width = json.optInt(KEY_WIDTH, 512),
                height = json.optInt(KEY_HEIGHT, 512),
                steps = json.optInt(KEY_STEPS, 20),
                cfg = json.optDouble(KEY_CFG, 7.0).toFloat(),
                seed = json.optString(KEY_SEED, ""),
                scheduler = json.optString(KEY_SCHEDULER, "dpm"),
            )
        }.getOrNull()
    }
}
