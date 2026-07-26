package io.github.xororz.localdream.data

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

enum class ModelContentRating(val serializedValue: String) {
    UNKNOWN("unknown"),
    SFW("sfw"),
    NSFW("nsfw"),
    ;

    companion object {
        fun fromSerialized(value: String?): ModelContentRating = entries.firstOrNull {
            it.serializedValue.equals(value, ignoreCase = true)
        } ?: UNKNOWN
    }
}

enum class ModelRatingSource(val serializedValue: String) {
    USER("user"),
    REPOSITORY_METADATA("repository_metadata"),
    REPOSITORY_NAME("repository_name"),
    ;

    companion object {
        fun fromSerialized(value: String?): ModelRatingSource? = entries.firstOrNull {
            it.serializedValue.equals(value, ignoreCase = true)
        }
    }
}

/**
 * Conservatively recovers the content label for models installed before
 * Vision Dream started writing app-owned metadata. Persisted metadata must
 * always win over this name-only fallback.
 */
fun inferLegacyModelContentRating(modelName: String): ModelContentRating {
    val normalized = modelName.lowercase(Locale.ROOT)
    val isUtility = LEGACY_UTILITY_NAME_MARKERS.any(normalized::contains)
    return if (!isUtility && normalized.contains("nsfw")) {
        ModelContentRating.NSFW
    } else {
        ModelContentRating.UNKNOWN
    }
}

fun resolveModelContentRating(
    metadata: ModelMetadata?,
    modelName: String,
): ModelContentRating = metadata?.contentRating
    ?: inferLegacyModelContentRating(modelName)

private val LEGACY_UTILITY_NAME_MARKERS = setOf(
    "filter",
    "checker",
    "detector",
    "classifier",
    "safety",
)

data class ModelSourceMetadata(
    val repositoryId: String,
    val revision: String?,
    val artifactKind: String,
)

/**
 * Versioned metadata owned by Vision Dream, separate from model-provided
 * generation defaults in config.json.
 */
data class ModelMetadata(
    val contentRating: ModelContentRating = ModelContentRating.UNKNOWN,
    val ratingSource: ModelRatingSource? = null,
    val ratingEvidence: Set<String> = emptySet(),
    val source: ModelSourceMetadata? = null,
) {
    fun toJsonString(): String = JSONObject().apply {
        put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        put(KEY_CONTENT_RATING, contentRating.serializedValue)
        ratingSource?.let { put(KEY_RATING_SOURCE, it.serializedValue) }
        if (ratingEvidence.isNotEmpty()) {
            put(
                KEY_RATING_EVIDENCE,
                JSONArray().apply { ratingEvidence.sorted().forEach { put(it) } },
            )
        }
        source?.let {
            put(
                KEY_SOURCE,
                JSONObject().apply {
                    put(KEY_REPOSITORY_ID, it.repositoryId)
                    it.revision?.let { revision -> put(KEY_REVISION, revision) }
                    put(KEY_ARTIFACT_KIND, it.artifactKind)
                },
            )
        }
    }.toString()

    companion object {
        const val SCHEMA_VERSION = 1

        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_CONTENT_RATING = "content_rating"
        private const val KEY_RATING_SOURCE = "rating_source"
        private const val KEY_RATING_EVIDENCE = "rating_evidence"
        private const val KEY_SOURCE = "source"
        private const val KEY_REPOSITORY_ID = "repository_id"
        private const val KEY_REVISION = "revision"
        private const val KEY_ARTIFACT_KIND = "artifact_kind"

        fun fromJsonString(rawJson: String): ModelMetadata {
            val json = JSONObject(rawJson)
            require(json.optInt(KEY_SCHEMA_VERSION, -1) == SCHEMA_VERSION) {
                "Unsupported model metadata schema"
            }
            val sourceJson = json.optJSONObject(KEY_SOURCE)
            val source = sourceJson?.let {
                val repositoryId = it.optString(KEY_REPOSITORY_ID).trim()
                val artifactKind = it.optString(KEY_ARTIFACT_KIND).trim()
                if (repositoryId.isEmpty() || artifactKind.isEmpty()) {
                    null
                } else {
                    ModelSourceMetadata(
                        repositoryId = repositoryId,
                        revision = it.optString(KEY_REVISION).trim().takeIf(String::isNotEmpty),
                        artifactKind = artifactKind,
                    )
                }
            }
            val evidence = buildSet {
                val values = json.optJSONArray(KEY_RATING_EVIDENCE) ?: JSONArray()
                for (index in 0 until values.length()) {
                    values.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
                }
            }
            return ModelMetadata(
                contentRating = ModelContentRating.fromSerialized(
                    json.optString(KEY_CONTENT_RATING),
                ),
                ratingSource = ModelRatingSource.fromSerialized(
                    json.optString(KEY_RATING_SOURCE),
                ),
                ratingEvidence = evidence,
                source = source,
            )
        }
    }
}

/**
 * Reads and atomically writes app-owned metadata inside a model directory.
 */
object ModelMetadataStore {
    const val FILE_NAME = ".vision-dream-model.json"

    fun read(modelDirectory: File): ModelMetadata? {
        val file = File(modelDirectory, FILE_NAME)
        if (!file.isFile) return null
        return runCatching { ModelMetadata.fromJsonString(file.readText()) }.getOrNull()
    }

    fun write(modelDirectory: File, metadata: ModelMetadata) {
        check(modelDirectory.isDirectory || modelDirectory.mkdirs()) {
            "Could not create model metadata directory"
        }
        val target = File(modelDirectory, FILE_NAME)
        val temporary = File(modelDirectory, "$FILE_NAME.tmp")
        temporary.writeText(metadata.toJsonString())
        try {
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
    }
}
