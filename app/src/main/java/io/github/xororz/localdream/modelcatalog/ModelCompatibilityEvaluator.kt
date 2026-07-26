package io.github.xororz.localdream.modelcatalog

import io.github.xororz.localdream.data.ModelFileLayouts
import java.util.Locale

/**
 * Fail-closed compatibility evaluator for remote model repositories.
 *
 * Raw checkpoints are accepted only when the model card explicitly identifies
 * Stable Diffusion 1.5 and the repository contains exactly one root checkpoint.
 * Pre-converted archives require a Local Dream marker or a known artifact
 * naming convention.
 */
class ModelCompatibilityEvaluator(
    private val forbiddenModelIds: Set<String> = emptySet(),
) {
    fun evaluate(repository: HuggingFaceModelRepository): ModelCompatibilityEvaluation {
        if (repository.isDisabled) {
            return rejected(CompatibilityRejection.DISABLED_REPOSITORY)
        }
        if (repository.isPrivate || repository.isGated) {
            return rejected(CompatibilityRejection.ACCESS_RESTRICTED)
        }
        if (isUnsupportedPipeline(repository)) {
            return rejected(CompatibilityRejection.UNSUPPORTED_PIPELINE)
        }

        val archiveArtifacts = compatibleArchives(repository)
        if (archiveArtifacts.isNotEmpty()) {
            return ModelCompatibilityEvaluation(archiveArtifacts.sortedBy { it.file.path.lowercase(Locale.ROOT) })
        }
        val directoryEvaluation = evaluateDirectories(repository)
        if (directoryEvaluation.isCompatible) return directoryEvaluation

        val checkpointEvaluation = evaluateCheckpoint(repository)
        return if (checkpointEvaluation.isCompatible) {
            checkpointEvaluation
        } else {
            checkpointEvaluation.copy(
                rejections = checkpointEvaluation.rejections + directoryEvaluation.rejections,
            )
        }
    }

    private fun compatibleArchives(repository: HuggingFaceModelRepository): List<CompatibleModelArtifact> {
        val repositoryMarked = isLocalDreamRepository(repository)
        return repository.files.mapNotNull { file ->
            if (!file.isRootFile || !file.path.endsWith(".zip", ignoreCase = true)) {
                return@mapNotNull null
            }
            val lowerName = file.path.lowercase(Locale.ROOT)
            if (archiveHasUnsupportedPurpose(lowerName)) {
                return@mapNotNull null
            }
            val qnnMatch = QNN_ARCHIVE.matchEntire(file.path)
            if (!repositoryMarked && qnnMatch == null) {
                return@mapNotNull null
            }

            val modelId = LocalModelId.fromArtifact(repository.id, file.path, forbiddenModelIds)
                ?: return@mapNotNull null
            val backendHint = when {
                qnnMatch != null -> CatalogBackendHint.QNN_NPU
                isMnnRepository(repository) -> CatalogBackendHint.MNN_CPU
                else -> CatalogBackendHint.PREPACKAGED
            }
            val backendType = inferArchiveBackendType(repository, backendHint)
                ?: return@mapNotNull null
            // A generic ZIP can self-identify as Local Dream compatible, but
            // NPU binaries are chipset-specific. Without the recognized QNN
            // filename convention there is no trustworthy hardware target, so
            // only the portable CPU backend may use the generic package path.
            if (backendHint == CatalogBackendHint.PREPACKAGED &&
                backendType != BACKEND_SD15_CPU
            ) {
                return@mapNotNull null
            }
            CompatibleModelArtifact(
                repositoryId = repository.id,
                repositorySha = repository.sha,
                file = file,
                localModelId = modelId,
                displayName = displayName(file.path),
                kind = CatalogArtifactKind.LOCAL_DREAM_ZIP,
                backendHint = backendHint,
                backendType = backendType,
                hardwareTarget = qnnMatch?.groupValues?.get(1)?.lowercase(Locale.ROOT),
            )
        }
    }

    private fun evaluateDirectories(
        repository: HuggingFaceModelRepository,
    ): ModelCompatibilityEvaluation {
        val rejections = linkedSetOf<CompatibilityRejection>()
        val candidates = repository.files
            .filter { isSafeLoosePath(it.path) }
            .groupBy { file ->
                val segments = file.path.split('/')
                when (segments.size) {
                    1 -> ""
                    2 -> segments.first()
                    else -> return@groupBy UNSUPPORTED_DIRECTORY_PREFIX
                }
            }
            .filterKeys { it != UNSUPPORTED_DIRECTORY_PREFIX }

        val artifacts = candidates.mapNotNull { (sourcePrefix, files) ->
            val filesByName = files.associateBy { it.path.substringAfterLast('/') }
            if (filesByName.size != files.size) return@mapNotNull null

            val layout = ModelFileLayouts.detect(filesByName.keys) ?: return@mapNotNull null
            val hardwareTarget = if (layout.requiresHardwareTarget) {
                inferDirectoryHardwareTarget(repository, sourcePrefix).also {
                    if (it == null) rejections += CompatibilityRejection.MISSING_HARDWARE_TARGET
                } ?: return@mapNotNull null
            } else {
                null
            }
            val localModelId = LocalModelId.fromDirectory(
                repositoryId = repository.id,
                directoryName = sourcePrefix.ifBlank { null },
                forbiddenIds = forbiddenModelIds,
            ) ?: run {
                rejections += CompatibilityRejection.UNSAFE_MODEL_ID
                return@mapNotNull null
            }
            val selectedFiles = files
                .filter { layout.accepts(it.path.substringAfterLast('/')) }
                .sortedBy { it.path.lowercase(Locale.ROOT) }
            CompatibleModelArtifact(
                repositoryId = repository.id,
                repositorySha = repository.sha,
                file = selectedFiles.first(),
                localModelId = localModelId,
                displayName = directoryDisplayName(repository.id, sourcePrefix),
                kind = CatalogArtifactKind.LOCAL_DREAM_DIRECTORY,
                backendHint = if (layout.backendType == BACKEND_SD15_CPU) {
                    CatalogBackendHint.MNN_CPU
                } else {
                    CatalogBackendHint.QNN_NPU
                },
                backendType = layout.backendType,
                hardwareTarget = hardwareTarget,
                directoryFiles = selectedFiles,
                sourcePrefix = sourcePrefix,
            )
        }
        return ModelCompatibilityEvaluation(
            artifacts = artifacts.sortedBy { it.displayName.lowercase(Locale.ROOT) },
            rejections = if (artifacts.isEmpty()) rejections else emptySet(),
        )
    }

    private fun evaluateCheckpoint(repository: HuggingFaceModelRepository): ModelCompatibilityEvaluation {
        val rejections = linkedSetOf<CompatibilityRejection>()
        val allCheckpointFiles = repository.files.filter {
            it.path.endsWith(".safetensors", ignoreCase = true)
        }
        val rootCheckpoints = allCheckpointFiles.filter(HuggingFaceModelFile::isRootFile)

        if (repository.files.any(::isShardFile) || allCheckpointFiles.any { SHARD_NAME.containsMatchIn(it.path) }) {
            rejections += CompatibilityRejection.SHARDED_CHECKPOINT
        }
        if (allCheckpointFiles.size != rootCheckpoints.size) {
            rejections += CompatibilityRejection.NESTED_CHECKPOINT
        }
        if (rootCheckpoints.size > 1) {
            rejections += CompatibilityRejection.MULTIPLE_CHECKPOINTS
        }
        if (isDiffusersRepository(repository)) {
            rejections += CompatibilityRejection.DIFFUSERS_LAYOUT
        }
        if (isInpainting(repository, rootCheckpoints)) {
            rejections += CompatibilityRejection.INPAINTING_MODEL
        }
        if (hasUnsupportedRawModelPurpose(repository, rootCheckpoints)) {
            rejections += CompatibilityRejection.AMBIGUOUS_MODEL_FAMILY
        }

        val baseFamily = classifyBaseModel(repository.baseModels)
        when (baseFamily) {
            BaseFamily.MISSING -> rejections += CompatibilityRejection.MISSING_EXPLICIT_SD15_BASE

            BaseFamily.AMBIGUOUS,
            BaseFamily.OTHER,
            -> rejections += CompatibilityRejection.AMBIGUOUS_MODEL_FAMILY

            BaseFamily.SD15 -> Unit
        }

        val checkpoint = rootCheckpoints.singleOrNull()
        if (checkpoint == null) {
            rejections += CompatibilityRejection.NO_SUPPORTED_ARTIFACT
        } else if (hasUnsupportedCheckpointName(checkpoint.path)) {
            rejections += CompatibilityRejection.AMBIGUOUS_MODEL_FAMILY
        }

        if (rejections.isNotEmpty() || checkpoint == null) {
            if (repository.files.any { it.path.endsWith(".zip", ignoreCase = true) }) {
                rejections += CompatibilityRejection.NOT_LOCAL_DREAM_ARCHIVE
            }
            return ModelCompatibilityEvaluation(emptyList(), rejections)
        }

        val modelId = LocalModelId.fromArtifact(repository.id, checkpoint.path, forbiddenModelIds)
            ?: return rejected(CompatibilityRejection.UNSAFE_MODEL_ID)
        return ModelCompatibilityEvaluation(
            artifacts = listOf(
                CompatibleModelArtifact(
                    repositoryId = repository.id,
                    repositorySha = repository.sha,
                    file = checkpoint,
                    localModelId = modelId,
                    displayName = displayName(checkpoint.path),
                    kind = CatalogArtifactKind.SD15_SAFETENSORS,
                    backendHint = CatalogBackendHint.SD15_CONVERSION,
                    backendType = BACKEND_SD15_CPU,
                ),
            ),
        )
    }

    private fun isUnsupportedPipeline(repository: HuggingFaceModelRepository): Boolean {
        val pipeline = repository.pipelineTag?.lowercase(Locale.ROOT)
        return pipeline != null && pipeline !in setOf("text-to-image", "image-to-image")
    }

    private fun inferDirectoryHardwareTarget(
        repository: HuggingFaceModelRepository,
        sourcePrefix: String,
    ): String? {
        // The directory name describes this artifact most precisely. Repositories
        // may contain one directory per chipset and advertise every target in
        // their tags, so combining all evidence would incorrectly reject each
        // individually identifiable directory as ambiguous.
        hardwareTargets(listOf(sourcePrefix)).singleOrNull()?.let { return it }
        hardwareTargets(listOf(repository.id)).singleOrNull()?.let { return it }
        return hardwareTargets(
            buildList {
                addAll(repository.tags)
                repository.cardMetadata.forEach { (key, values) ->
                    if (normalizeMetadataKey(key) in HARDWARE_METADATA_KEYS) {
                        addAll(values)
                    }
                }
            },
        ).singleOrNull()
    }

    private fun hardwareTargets(values: Iterable<String>): List<String> = values.mapNotNull { value ->
        HARDWARE_TARGET.find(value)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
    }.distinct()

    private fun normalizeMetadataKey(value: String): String = value
        .trim()
        .lowercase(Locale.ROOT)
        .replace('_', '-')

    private fun isSafeLoosePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.endsWith('/') || '\\' in path) {
            return false
        }
        if (path.any(Char::isISOControl)) return false
        val segments = path.split('/')
        return segments.size <= 2 &&
            segments.all { it.isNotBlank() && it != "." && it != ".." }
    }

    private fun directoryDisplayName(repositoryId: String, sourcePrefix: String): String {
        val rawName = sourcePrefix.ifBlank { repositoryId.substringAfterLast('/') }
        return rawName.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
    }

    private fun isLocalDreamRepository(repository: HuggingFaceModelRepository): Boolean {
        if (repository.id.lowercase(Locale.ROOT) in OFFICIAL_LOCAL_DREAM_REPOSITORIES) {
            return true
        }
        val markers = buildSet {
            addAll(repository.tags)
            repository.libraryName?.let(::add)
            repository.modelType?.let(::add)
            addAll(repository.formats)
            repository.cardMetadata.forEach { (key, values) ->
                if (key.lowercase(Locale.ROOT) in LOCAL_DREAM_METADATA_KEYS) {
                    addAll(values)
                }
            }
        }.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT).replace('_', '-') }
        return markers.any { it in LOCAL_DREAM_MARKERS } ||
            repository.cardMetadata.any { (key, values) ->
                key.lowercase(Locale.ROOT) in LOCAL_DREAM_METADATA_KEYS &&
                    values.any { it.equals("true", true) || it == "1" || it.equals("yes", true) }
            }
    }

    private fun isMnnRepository(repository: HuggingFaceModelRepository): Boolean = repository.id.equals("xororz/sd-mnn", ignoreCase = true) ||
        repository.tags.any { it.equals("mnn", ignoreCase = true) } ||
        repository.formats.any { it.equals("mnn", ignoreCase = true) }

    private fun inferArchiveBackendType(
        repository: HuggingFaceModelRepository,
        hint: CatalogBackendHint,
    ): String? = when (hint) {
        CatalogBackendHint.MNN_CPU -> BACKEND_SD15_CPU

        CatalogBackendHint.QNN_NPU -> {
            val familyMetadata = buildList {
                add(repository.id)
                addAll(repository.tags)
                addAll(repository.baseModels)
                repository.modelType?.let(::add)
                addAll(repository.formats)
            }.joinToString(" ").lowercase(Locale.ROOT)
            when {
                "anima" in familyMetadata -> BACKEND_ANIMA
                "sdxl" in familyMetadata || "stable-diffusion-xl" in familyMetadata -> BACKEND_SDXL
                else -> BACKEND_SD15_NPU
            }
        }

        CatalogBackendHint.PREPACKAGED ->
            repository.modelType
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it in SUPPORTED_BACKEND_TYPES }

        CatalogBackendHint.SD15_CONVERSION -> BACKEND_SD15_CPU
    }

    private fun archiveHasUnsupportedPurpose(lowerName: String): Boolean = UNSUPPORTED_ARTIFACT_TOKENS.any(lowerName::contains)

    private fun isDiffusersRepository(repository: HuggingFaceModelRepository): Boolean {
        if (repository.libraryName.equals("diffusers", ignoreCase = true)) return true
        if (repository.tags.any { it.equals("diffusers", true) || it.startsWith("diffusers:", true) }) return true
        if (repository.configClassName?.contains("Pipeline", ignoreCase = true) == true) return true
        return repository.files.any { file ->
            val lower = file.path.lowercase(Locale.ROOT)
            lower == "model_index.json" ||
                DIFFUSERS_DIRECTORIES.any { lower.startsWith("$it/") }
        }
    }

    private fun isInpainting(
        repository: HuggingFaceModelRepository,
        checkpoints: List<HuggingFaceModelFile>,
    ): Boolean {
        val metadata = buildList {
            add(repository.id)
            addAll(repository.tags)
            repository.pipelineTag?.let(::add)
            repository.configClassName?.let(::add)
            addAll(repository.baseModels)
            addAll(checkpoints.map { it.path })
        }
        return metadata.any { it.contains("inpaint", ignoreCase = true) }
    }

    private fun hasUnsupportedRawModelPurpose(
        repository: HuggingFaceModelRepository,
        checkpoints: List<HuggingFaceModelFile>,
    ): Boolean {
        val metadata = buildList {
            add(repository.id)
            addAll(repository.tags)
            repository.libraryName?.let(::add)
            repository.modelType?.let(::add)
            repository.configClassName?.let(::add)
            addAll(repository.formats)
            addAll(checkpoints.map { it.path })
        }.map { it.lowercase(Locale.ROOT) }
        return metadata.any { value ->
            UNSUPPORTED_RAW_MODEL_TOKENS.any(value::contains) ||
                SD15_CONTRADICTIONS.any(value::contains) ||
                XL_TOKEN.containsMatchIn(value)
        }
    }

    private fun classifyBaseModel(baseModels: Set<String>): BaseFamily {
        if (baseModels.isEmpty()) return BaseFamily.MISSING
        val normalized = baseModels.map { normalizeBaseModel(it) }.filter { it.isNotBlank() }
        if (normalized.isEmpty()) return BaseFamily.MISSING

        val hasSd15 = normalized.any(::isExplicitSd15)
        val hasContradiction = normalized.any {
            SD15_CONTRADICTIONS.any(it::contains) || (!isExplicitSd15(it) && it !in ALLOWED_SD15_ALIASES)
        }
        return when {
            hasSd15 && !hasContradiction -> BaseFamily.SD15
            hasSd15 -> BaseFamily.AMBIGUOUS
            else -> BaseFamily.OTHER
        }
    }

    private fun normalizeBaseModel(value: String): String = value
        .substringBefore('@')
        .trim()
        .lowercase(Locale.ROOT)
        .replace('_', '-')
        .replace(' ', '-')

    private fun isExplicitSd15(value: String): Boolean = value in ALLOWED_SD15_ALIASES ||
        value.substringAfterLast('/') in ALLOWED_SD15_ALIASES

    private fun hasUnsupportedCheckpointName(path: String): Boolean {
        val lower = path.lowercase(Locale.ROOT)
        return UNSUPPORTED_ARTIFACT_TOKENS.any(lower::contains) ||
            SD15_CONTRADICTIONS.any(lower::contains) ||
            SHARD_NAME.containsMatchIn(lower)
    }

    private fun isShardFile(file: HuggingFaceModelFile): Boolean {
        val lower = file.path.lowercase(Locale.ROOT)
        return lower.endsWith(".safetensors.index.json") || lower.endsWith(".index.json")
    }

    private fun displayName(path: String): String {
        val stem = path.substringBeforeLast('.', path)
            .replace(QNN_SUFFIX_FOR_DISPLAY, "")
        return stem.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
    }

    private fun rejected(rejection: CompatibilityRejection): ModelCompatibilityEvaluation = ModelCompatibilityEvaluation(emptyList(), setOf(rejection))

    private enum class BaseFamily {
        MISSING,
        SD15,
        AMBIGUOUS,
        OTHER,
    }

    private companion object {
        val QNN_ARCHIVE =
            Regex("(?i)^.+_qnn\\d+(?:\\.\\d+)?_(min|8gen[1-9]|8sgen[1-9]|8elite)\\.zip$")
        val QNN_SUFFIX_FOR_DISPLAY =
            Regex("(?i)_qnn\\d+(?:\\.\\d+)?_(?:min|8gen[1-9]|8sgen[1-9]|8elite)$")
        val HARDWARE_TARGET =
            Regex("(?i)(?:^|[^a-z0-9])(min|8gen[1-9]|8sgen[1-9]|8elite)(?:$|[^a-z0-9])")
        val SHARD_NAME = Regex("(?i)-\\d{5}-of-\\d{5}\\.safetensors$")
        const val UNSUPPORTED_DIRECTORY_PREFIX = "\u0000"

        val OFFICIAL_LOCAL_DREAM_REPOSITORIES = setOf(
            "xororz/sd-qnn",
            "xororz/sdxl-qnn",
            "xororz/sd-mnn",
        )
        val LOCAL_DREAM_METADATA_KEYS = setOf(
            "local_dream_compatible",
            "localdream_compatible",
            "vision_dream_compatible",
            "format",
            "formats",
        )
        val LOCAL_DREAM_MARKERS = setOf(
            "local-dream",
            "localdream",
            "vision-dream",
            "visiondream",
        )
        val HARDWARE_METADATA_KEYS = setOf(
            "hardware-target",
            "qnn-target",
            "chipset",
            "soc",
        )
        val DIFFUSERS_DIRECTORIES = setOf(
            "feature_extractor",
            "safety_checker",
            "scheduler",
            "text_encoder",
            "text_encoder_2",
            "tokenizer",
            "tokenizer_2",
            "unet",
            "vae",
        )
        val UNSUPPORTED_ARTIFACT_TOKENS = setOf(
            "inpaint",
            "controlnet",
            "control_net",
            "lora",
            "lycoris",
            "textual_inversion",
            "embedding",
            "workflow",
            "upscale",
            "vae",
        )
        val ALLOWED_SD15_ALIASES = setOf(
            "runwayml/stable-diffusion-v1-5",
            "stable-diffusion-v1-5/stable-diffusion-v1-5",
            "stable-diffusion-v1-5",
            "sd-v1-5",
            "sd-1.5",
            "sd1.5",
            "sd15",
        )
        val SD15_CONTRADICTIONS = setOf(
            "sdxl",
            "stable-diffusion-xl",
            "sd-2",
            "stable-diffusion-2",
            "sd3",
            "stable-diffusion-3",
            "flux",
            "illustrious",
            "pony",
            "anima",
            "krea",
        )
        val UNSUPPORTED_RAW_MODEL_TOKENS = setOf(
            "controlnet",
            "control-net",
            "lora",
            "lycoris",
            "textual-inversion",
            "textual_inversion",
            "embedding",
            "upscaler",
        )
        val XL_TOKEN = Regex("(^|[-_/])xl($|[-_/])")
        const val BACKEND_SD15_CPU = "sd15cpu"
        const val BACKEND_SD15_NPU = "sd15npu"
        const val BACKEND_SDXL = "sdxl"
        const val BACKEND_ANIMA = "anima"
        val SUPPORTED_BACKEND_TYPES = setOf(
            BACKEND_SD15_CPU,
            BACKEND_SD15_NPU,
            BACKEND_SDXL,
            BACKEND_ANIMA,
        )
    }
}
