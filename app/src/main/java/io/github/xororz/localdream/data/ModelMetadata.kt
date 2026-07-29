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
 * A model's explicit contract with the packaged NPU runtime.  These values
 * come from the model build/validation input; they are deliberately not
 * inferred from a directory name or a runtime library filename.
 */
data class ModelRuntimeCompatibility(
    val qairtVersion: String,
    val abi: String,
    val htpTarget: String,
    val contextFingerprint: String,
)

/**
 * App-owned evidence that this exact model context completed native inference.
 * This type is intentionally kept separate from public model metadata: only
 * [NativeRuntimeAttestationStore] can persist it in app-private storage.
 */
data class NativeRuntimeAttestation(
    val deviceModel: String,
    val soc: String,
    val qairtVersion: String,
    val abi: String,
    val htpTarget: String,
    val contextFingerprint: String,
    val loadedLibraryFingerprints: Map<String, String>,
    val observedAtEpochMillis: Long,
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
    val runtimeCompatibility: ModelRuntimeCompatibility? = null,
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
        runtimeCompatibility?.let {
            put(
                KEY_RUNTIME_COMPATIBILITY,
                JSONObject().apply {
                    put(KEY_QAIRT_VERSION, it.qairtVersion)
                    put(KEY_ABI, it.abi)
                    put(KEY_HTP_TARGET, it.htpTarget)
                    put(KEY_CONTEXT_FINGERPRINT, it.contextFingerprint)
                },
            )
        }
    }.toString()

    companion object {
        const val SCHEMA_VERSION = 3

        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_CONTENT_RATING = "content_rating"
        private const val KEY_RATING_SOURCE = "rating_source"
        private const val KEY_RATING_EVIDENCE = "rating_evidence"
        private const val KEY_SOURCE = "source"
        private const val KEY_REPOSITORY_ID = "repository_id"
        private const val KEY_REVISION = "revision"
        private const val KEY_ARTIFACT_KIND = "artifact_kind"
        private const val KEY_RUNTIME_COMPATIBILITY = "runtime_compatibility"
        private const val KEY_QAIRT_VERSION = "qairt_version"
        private const val KEY_ABI = "abi"
        private const val KEY_HTP_TARGET = "htp_target"
        private const val KEY_CONTEXT_FINGERPRINT = "context_fingerprint"

        fun fromJsonString(rawJson: String): ModelMetadata {
            val json = JSONObject(rawJson)
            val schemaVersion = json.optInt(KEY_SCHEMA_VERSION, -1)
            require(schemaVersion in 1..SCHEMA_VERSION) {
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
            val runtimeCompatibility = json.optJSONObject(KEY_RUNTIME_COMPATIBILITY)?.let {
                val qairtVersion = it.optString(KEY_QAIRT_VERSION).trim()
                val abi = it.optString(KEY_ABI).trim()
                val htpTarget = it.optString(KEY_HTP_TARGET).trim()
                val contextFingerprint = it.optString(KEY_CONTEXT_FINGERPRINT).trim()
                if (
                    qairtVersion.isEmpty() || abi.isEmpty() || htpTarget.isEmpty() ||
                    contextFingerprint.isEmpty()
                ) {
                    null
                } else {
                    ModelRuntimeCompatibility(
                        qairtVersion = qairtVersion,
                        abi = abi,
                        htpTarget = htpTarget,
                        contextFingerprint = contextFingerprint,
                    )
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
                runtimeCompatibility = runtimeCompatibility,
            )
        }
    }
}

/** Builds a persisted attestation only from a completed native generation. */
object NativeRuntimeAttestor {
    private const val FALLBACK_REASON = "COMPATIBILITY_FALLBACK_REQUIRED"

    fun attest(
        probe: RuntimeProbe,
        observedAtEpochMillis: Long,
    ): NativeRuntimeAttestation? {
        val compatibility = RuntimeProbeEvaluator.targetCompatibility(probe) ?: return null
        if ((probe.rejectionReasons - FALLBACK_REASON).isNotEmpty() || observedAtEpochMillis <= 0L) {
            return null
        }
        return NativeRuntimeAttestation(
            deviceModel = requireNotNull(probe.deviceModel),
            soc = requireNotNull(probe.soc),
            qairtVersion = compatibility.qairtVersion,
            abi = compatibility.abi,
            htpTarget = compatibility.htpTarget,
            contextFingerprint = compatibility.contextFingerprint,
            loadedLibraryFingerprints = probe.loadedLibraryFingerprints.toSortedMap(),
            observedAtEpochMillis = observedAtEpochMillis,
        )
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
