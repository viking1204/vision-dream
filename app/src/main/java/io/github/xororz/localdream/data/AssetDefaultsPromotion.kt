package io.github.xororz.localdream.data

/**
 * The subset of a stored asset's parameters that can be promoted to a model's
 * defaults.
 *
 * Kept independent of the UI's `GenerationParameters` so the merge rules stay
 * unit-testable without dragging in Compose or Android.
 */
data class AssetDefaultsCandidate(
    val prompt: String,
    val negativePrompt: String,
    val steps: Int,
    val cfg: Float,
    val width: Int,
    val height: Int,
    val scheduler: String,
)

/**
 * "Use this image's settings from now on."
 *
 * Promotion is intentionally conservative: an asset may come from a different
 * model, an older app version, or a hand-edited database, so every field is
 * validated before it is allowed to overwrite a default. Anything that fails
 * validation leaves the existing default untouched rather than writing a value
 * the run screen would refuse to load.
 */
object AssetDefaultsPromotion {
    const val MIN_DIMENSION = 128
    const val MAX_DIMENSION = 2048
    private const val DIMENSION_STRIDE = 64
    private const val MAX_STEPS = 50
    private const val MAX_CFG = 30f

    /** Scheduler ids the native backend knows how to construct. */
    val SUPPORTED_SCHEDULERS = listOf(
        "dpm",
        "dpm_karras",
        "dpm_sde",
        "dpm_sde_karras",
        "euler",
        "euler_karras",
        "euler_a",
        "euler_a_karras",
        "lcm",
    )

    /**
     * Merges [candidate] into [current] and returns the values to persist.
     *
     * Notable non-promotions:
     * - **Seed** is never copied. Pinning a model to one seed would make every
     *   later run reproduce the same picture, which is not what a user means by
     *   "use these settings".
     * - **Size** is all-or-nothing. Promoting a valid width next to a rejected
     *   height would silently change the aspect ratio.
     * - An **empty prompt** is treated as "nothing to say", not as an
     *   instruction to erase the current default. An empty *negative* prompt is
     *   honoured, because clearing it is a deliberate, common choice.
     */
    fun promote(current: GenerationPrefs, candidate: AssetDefaultsCandidate): GenerationPrefs {
        val sizeIsUsable = candidate.width.isUsableDimension() &&
            candidate.height.isUsableDimension()
        return current.copy(
            hasSaved = true,
            prompt = candidate.prompt.ifBlank { current.prompt },
            negativePrompt = candidate.negativePrompt,
            steps = if (candidate.steps in 1..MAX_STEPS) {
                candidate.steps.toFloat()
            } else {
                current.steps
            },
            cfg = if (candidate.cfg.isFinite() && candidate.cfg in 0f..MAX_CFG) {
                candidate.cfg
            } else {
                current.cfg
            },
            width = if (sizeIsUsable) candidate.width else current.width,
            height = if (sizeIsUsable) candidate.height else current.height,
            scheduler = if (candidate.scheduler in SUPPORTED_SCHEDULERS) {
                candidate.scheduler
            } else {
                current.scheduler
            },
        )
    }

    private fun Int.isUsableDimension(): Boolean = this in MIN_DIMENSION..MAX_DIMENSION && this % DIMENSION_STRIDE == 0
}
