package io.github.xororz.localdream.data

/**
 * Runtime file contract for one supported generation backend.
 *
 * Search filtering, installation, and runtime validation must share this
 * contract so a repository cannot be accepted with a weaker definition than
 * the native backend uses when it starts.
 */
data class ModelFileLayout(
    val backendType: String,
    val requiredFiles: Set<String>,
    val optionalFiles: Set<String>,
    val completionMarker: String,
    val requiresHardwareTarget: Boolean,
) {
    fun isComplete(fileNames: Set<String>): Boolean = requiredFiles.all(fileNames::contains)

    fun accepts(fileName: String): Boolean = fileName in requiredFiles ||
        fileName in optionalFiles ||
        PATCH_FILE.matches(fileName)

    private companion object {
        val PATCH_FILE = Regex("""\d+(?:x\d+)?\.patch""")
    }
}

/**
 * Single source of truth for model layouts supported by the native runtime.
 */
object ModelFileLayouts {
    private val commonOptionalFiles = setOf(
        "config.json",
        "V_PRED",
    )

    val sd15Cpu = ModelFileLayout(
        backendType = "sd15cpu",
        requiredFiles = setOf(
            "tokenizer.json",
            "clip_v2.mnn",
            "pos_emb.bin",
            "token_emb.bin",
            "unet.mnn",
            "vae_decoder.mnn",
        ),
        optionalFiles = commonOptionalFiles + "vae_encoder.mnn",
        completionMarker = "finished",
        requiresHardwareTarget = false,
    )

    val sd15Npu = ModelFileLayout(
        backendType = "sd15npu",
        requiredFiles = setOf(
            "tokenizer.json",
            "clip_v2.mnn",
            "pos_emb.bin",
            "token_emb.bin",
            "unet.bin",
            "vae_decoder.bin",
        ),
        optionalFiles = commonOptionalFiles + "vae_encoder.bin",
        completionMarker = "npucustom",
        requiresHardwareTarget = true,
    )

    val sdxl = ModelFileLayout(
        backendType = "sdxl",
        requiredFiles = setOf(
            "tokenizer.json",
            "clip.mnn",
            "clip_2.mnn",
            "pos_emb.bin",
            "pos_emb_2.bin",
            "token_emb.bin",
            "token_emb_2.bin",
            "unet.bin",
            "vae_decoder.bin",
        ),
        optionalFiles = commonOptionalFiles + "vae_encoder.bin",
        completionMarker = "SDXL",
        requiresHardwareTarget = true,
    )

    val anima = ModelFileLayout(
        backendType = "anima",
        requiredFiles = setOf(
            "tokenizer.json",
            "tokenizer_t5.json",
            "token_emb.bin",
            "clip.bin",
            "unet_part1.bin",
            "unet_part2.bin",
            "vae_decoder.bin",
        ),
        optionalFiles = commonOptionalFiles + "vae_encoder.bin",
        completionMarker = "ANIMA",
        requiresHardwareTarget = true,
    )

    val all: List<ModelFileLayout> = listOf(anima, sdxl, sd15Cpu, sd15Npu)

    fun forBackend(backendType: String): ModelFileLayout? = all.firstOrNull {
        it.backendType == backendType
    }

    /**
     * Returns a layout only when the file set identifies exactly one backend.
     */
    fun detect(fileNames: Set<String>): ModelFileLayout? = all
        .filter { it.isComplete(fileNames) }
        .singleOrNull()
}
