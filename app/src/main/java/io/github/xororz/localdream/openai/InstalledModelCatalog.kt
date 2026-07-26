package io.github.xororz.localdream.openai

import android.content.Context
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.data.ModelFileLayouts
import io.github.xororz.localdream.data.ModelRepository
import io.github.xororz.localdream.data.UpscalerRepository
import java.io.File

/**
 * Runtime-validated model catalog used by the network API.
 *
 * The regular model list intentionally tolerates legacy installs. A network
 * request cannot do that: accepting a half-downloaded directory would tear
 * down the current backend before the replacement fails to start.
 */
class InstalledModelCatalog(private val context: Context) {
    data class Entry(
        val id: String,
        val name: String,
        val kind: Kind,
        val backendType: String,
        val generationSize: Int,
        val supportsImageInput: Boolean,
        val model: Model? = null,
        val upscalerFile: File? = null,
    )

    enum class Kind {
        GENERATION,
        UPSCALER,
    }

    suspend fun all(): List<Entry> {
        val modelRepository = ModelRepository.getInstance(context)
        val upscalerRepository = UpscalerRepository.getInstance(context)
        modelRepository.refreshAllModels()
        upscalerRepository.ensureLoaded()

        val generationModels = modelRepository.models.mapNotNull(::validatedGenerationModel)
        val upscalers = upscalerRepository.upscalers.mapNotNull { upscaler ->
            val file = File(
                File(Model.getModelsDir(context), upscaler.id),
                Model.UPSCALER_FILE_NAME,
            )
            if (!isNonEmptyFile(file)) return@mapNotNull null
            Entry(
                id = upscaler.id,
                name = upscaler.name,
                kind = Kind.UPSCALER,
                backendType = "upscaler",
                generationSize = 0,
                supportsImageInput = true,
                upscalerFile = file,
            )
        }
        return generationModels + upscalers
    }

    suspend fun find(id: String): Entry? = all().firstOrNull { it.id == id }

    private fun validatedGenerationModel(model: Model): Entry? {
        if (!model.isDownloaded) return null
        val directory = File(Model.getModelsDir(context), model.id)
        val required = requiredFiles(model.backendType)
        if (required.isEmpty()) return null
        if (required.any { !isNonEmptyFile(File(directory, it)) }) return null

        return Entry(
            id = model.id,
            name = model.name,
            kind = Kind.GENERATION,
            backendType = model.backendType,
            generationSize = model.generationSize,
            // The gateway advertises an installed model's actual runtime
            // capability. The local UI preference only controls whether the
            // in-app img2img controls/startup path opt into that capability.
            supportsImageInput = supportsImageInput(directory, model.backendType),
            model = model,
        )
    }

    companion object {
        internal fun requiredFiles(backendType: String): Set<String> = ModelFileLayouts.forBackend(backendType)?.requiredFiles.orEmpty()

        internal fun isNonEmptyFile(file: File): Boolean = file.isFile && file.length() > 0L

        internal fun supportsImageInput(directory: File, backendType: String): Boolean {
            val encoderName = when (backendType) {
                "sd15cpu" -> "vae_encoder.mnn"
                "sd15npu", "sdxl", "anima" -> "vae_encoder.bin"
                else -> return false
            }
            return isNonEmptyFile(File(directory, encoderName))
        }
    }
}
