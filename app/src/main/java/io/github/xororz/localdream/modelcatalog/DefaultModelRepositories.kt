package io.github.xororz.localdream.modelcatalog

/**
 * Curated Hugging Face repositories that ship with vision-dream so users can
 * browse and install models without those models being hardcoded in the app.
 *
 * Every entry is a Qualcomm NPU (QNN) capable image-generation repository. The
 * [ModelCompatibilityEvaluator] detects the QNN archives inside each repo, and
 * the per-device compatibility filter ([CatalogDeviceCompatibility]) drops
 * anything the current SoC cannot run (e.g. SDXL QNN models on a
 * non-SDXL-capable device).
 *
 * Listing repositories here (rather than individual models in `Model.kt`)
 * keeps the default catalog data-driven: adding a new supported repository is a
 * one-line change and does not require a model registry entry.
 */
object DefaultModelRepositories {
    data class Entry(
        val repoId: String,
        val displayName: String,
    )

    val repositories: List<Entry> = listOf(
        Entry("xororz/sd-qnn", "SD1.5 QNN (Qualcomm NPU)"),
        Entry("Mr-J-369/StableDiffusion-SD1.5-qnn2.28", "Stable Diffusion 1.5 QNN 2.28"),
        Entry("YuuiKurata/novaMatureXL_qnn2.28", "NovaMature XL QNN 2.28"),
        Entry("xororz/sdxl-qnn", "SDXL QNN (Qualcomm NPU)"),
        Entry("xororz/anima-qnn", "Anima QNN (Qualcomm NPU)"),
    )
}
