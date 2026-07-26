package io.github.xororz.localdream.modelcatalog

import io.github.xororz.localdream.data.ModelContentRating
import io.github.xororz.localdream.data.ModelMetadata
import io.github.xororz.localdream.data.ModelRatingSource
import io.github.xororz.localdream.data.ModelSourceMetadata

/**
 * A file advertised by a Hugging Face-compatible model repository.
 *
 * [path] is always the repository-relative path returned by the remote API. It
 * must not be used as a local filesystem path without validation.
 */
data class HuggingFaceModelFile(
    val path: String,
    val sizeBytes: Long? = null,
    val lfsSha256: String? = null,
) {
    val isRootFile: Boolean
        get() = path.isNotBlank() && '/' !in path && '\\' !in path
}

/**
 * The subset of repository metadata needed to decide whether Vision Dream can
 * safely import an artifact.
 */
data class HuggingFaceModelRepository(
    val id: String,
    val author: String? = null,
    val sha: String? = null,
    val pipelineTag: String? = null,
    val libraryName: String? = null,
    val configClassName: String? = null,
    val tags: Set<String> = emptySet(),
    val baseModels: Set<String> = emptySet(),
    val modelType: String? = null,
    val formats: Set<String> = emptySet(),
    val cardMetadata: Map<String, Set<String>> = emptyMap(),
    val files: List<HuggingFaceModelFile> = emptyList(),
    val downloads: Long? = null,
    val likes: Long? = null,
    val lastModified: String? = null,
    val isPrivate: Boolean = false,
    val isGated: Boolean = false,
    val isDisabled: Boolean = false,
    val declaredNsfw: Boolean? = null,
)

enum class CatalogArtifactKind {
    LOCAL_DREAM_ZIP,
    LOCAL_DREAM_DIRECTORY,
    SD15_SAFETENSORS,
}

enum class CatalogBackendHint {
    QNN_NPU,
    MNN_CPU,
    PREPACKAGED,
    SD15_CONVERSION,
}

/**
 * A repository artifact that passed the fail-closed compatibility checks.
 */
data class CompatibleModelArtifact(
    val repositoryId: String,
    val repositorySha: String?,
    val file: HuggingFaceModelFile,
    val localModelId: String,
    val displayName: String,
    val kind: CatalogArtifactKind,
    val backendHint: CatalogBackendHint,
    val backendType: String?,
    val hardwareTarget: String? = null,
    val directoryFiles: List<HuggingFaceModelFile> = emptyList(),
    val sourcePrefix: String = "",
) {
    val isArchive: Boolean
        get() = kind == CatalogArtifactKind.LOCAL_DREAM_ZIP

    val requiresConversion: Boolean
        get() = kind == CatalogArtifactKind.SD15_SAFETENSORS

    val files: List<HuggingFaceModelFile>
        get() = if (kind == CatalogArtifactKind.LOCAL_DREAM_DIRECTORY) {
            directoryFiles
        } else {
            listOf(file)
        }
}

enum class CompatibilityRejection {
    ACCESS_RESTRICTED,
    DISABLED_REPOSITORY,
    UNSUPPORTED_PIPELINE,
    NOT_LOCAL_DREAM_ARCHIVE,
    MISSING_EXPLICIT_SD15_BASE,
    AMBIGUOUS_MODEL_FAMILY,
    DIFFUSERS_LAYOUT,
    INPAINTING_MODEL,
    NESTED_CHECKPOINT,
    SHARDED_CHECKPOINT,
    MULTIPLE_CHECKPOINTS,
    MISSING_HARDWARE_TARGET,
    UNSAFE_MODEL_ID,
    NO_SUPPORTED_ARTIFACT,
}

data class ModelCompatibilityEvaluation(
    val artifacts: List<CompatibleModelArtifact>,
    val rejections: Set<CompatibilityRejection> = emptySet(),
) {
    val isCompatible: Boolean
        get() = artifacts.isNotEmpty()
}

/**
 * Flattened search item intended for the download and presentation layers.
 */
data class ModelCatalogSearchResult(
    val repositoryId: String,
    val localModelId: String,
    val displayName: String,
    val artifactFileName: String,
    val downloadUrl: String,
    val artifactKind: CatalogArtifactKind,
    val backendHint: CatalogBackendHint,
    val backendType: String?,
    val hardwareTarget: String?,
    val sizeBytes: Long?,
    val lastModified: String?,
    val sha256: String? = null,
    val downloadManifest: CatalogDownloadManifest? = null,
    val contentRating: ModelContentRating = ModelContentRating.UNKNOWN,
    val ratingSource: ModelRatingSource? = null,
    val ratingEvidence: Set<String> = emptySet(),
    val repositoryRevision: String? = null,
) {
    fun installExpectation(): CatalogInstallExpectation = CatalogInstallExpectation(
        backendType = requireNotNull(backendType) { "Catalog result has no backend" },
        hardwareTarget = hardwareTarget,
    )

    fun installationMetadata(): ModelMetadata = ModelMetadata(
        contentRating = contentRating,
        ratingSource = ratingSource,
        ratingEvidence = ratingEvidence,
        source = ModelSourceMetadata(
            repositoryId = repositoryId,
            revision = repositoryRevision,
            artifactKind = artifactKind.name.lowercase(),
        ),
    )
}
