package io.github.xororz.localdream.modelcatalog

import io.github.xororz.localdream.data.ModelContentRating
import io.github.xororz.localdream.data.ModelRatingSource
import io.github.xororz.localdream.data.inferLegacyModelContentRating
import java.util.Locale

data class ModelContentClassification(
    val rating: ModelContentRating,
    val source: ModelRatingSource?,
    val evidence: Set<String> = emptySet(),
)

/**
 * Conservative NSFW classifier for repository metadata.
 *
 * Missing evidence stays UNKNOWN. The classifier never treats the absence of
 * an NSFW marker as proof that a model is safe.
 */
object ModelContentClassifier {
    fun classify(
        repository: HuggingFaceModelRepository,
        artifactHint: String? = null,
    ): ModelContentClassification {
        if (repository.declaredNsfw == true) {
            return nsfw(ModelRatingSource.REPOSITORY_METADATA, "declared_nsfw")
        }

        val tagEvidence = repository.tags.mapNotNullTo(mutableSetOf()) { tag ->
            normalizeMarker(tag).takeIf(NSFW_MARKERS::contains)?.let { "tag:$it" }
        }
        if (tagEvidence.isNotEmpty()) {
            return ModelContentClassification(
                rating = ModelContentRating.NSFW,
                source = ModelRatingSource.REPOSITORY_METADATA,
                evidence = tagEvidence,
            )
        }

        val metadataEvidence = buildSet {
            repository.cardMetadata.forEach { (rawKey, rawValues) ->
                val key = normalizeMarker(rawKey)
                if (key !in CONTENT_RATING_KEYS) return@forEach
                rawValues.forEach { rawValue ->
                    val value = normalizeMarker(rawValue)
                    val positiveBoolean = key in BOOLEAN_RATING_KEYS && value in TRUE_VALUES
                    if (positiveBoolean || value in NSFW_MARKERS) {
                        add("metadata:$key=$value")
                    }
                }
            }
        }
        if (metadataEvidence.isNotEmpty()) {
            return ModelContentClassification(
                rating = ModelContentRating.NSFW,
                source = ModelRatingSource.REPOSITORY_METADATA,
                evidence = metadataEvidence,
            )
        }

        // Explicit false suppresses the weakest name-only heuristic, but never
        // overrides an explicit positive tag or card field handled above.
        if (repository.declaredNsfw == false) return unknown()

        val nameHints = listOf(repository.id, artifactHint.orEmpty())
        val nameTokens = nameHints.flatMapTo(mutableSetOf(), ::tokenize)
        val isUtility = nameTokens.any(UTILITY_TOKENS::contains)
        val matchedTokens = nameTokens.filterTo(mutableSetOf()) { it in NAME_NSFW_TOKENS }
        val hasEmbeddedNsfw = nameHints.any {
            inferLegacyModelContentRating(it) == ModelContentRating.NSFW
        }
        return if (!isUtility && (matchedTokens.isNotEmpty() || hasEmbeddedNsfw)) {
            ModelContentClassification(
                rating = ModelContentRating.NSFW,
                source = ModelRatingSource.REPOSITORY_NAME,
                evidence = matchedTokens.mapTo(mutableSetOf()) { "name:$it" }.apply {
                    if (hasEmbeddedNsfw) add("name:nsfw")
                },
            )
        } else {
            unknown()
        }
    }

    private fun nsfw(
        source: ModelRatingSource,
        evidence: String,
    ): ModelContentClassification = ModelContentClassification(
        rating = ModelContentRating.NSFW,
        source = source,
        evidence = setOf(evidence),
    )

    private fun unknown(): ModelContentClassification = ModelContentClassification(
        rating = ModelContentRating.UNKNOWN,
        source = null,
    )

    private fun normalizeMarker(value: String): String = value
        .trim()
        .lowercase(Locale.ROOT)
        .replace('_', '-')
        .replace(Regex("""\s+"""), "-")

    private fun tokenize(value: String): Set<String> = value
        .lowercase(Locale.ROOT)
        .split(Regex("""[^a-z0-9+]+"""))
        .filterTo(mutableSetOf(), String::isNotBlank)

    private val CONTENT_RATING_KEYS = setOf(
        "nsfw",
        "adult",
        "explicit",
        "content-rating",
        "rating",
    )
    private val BOOLEAN_RATING_KEYS = setOf("nsfw", "adult", "explicit")
    private val TRUE_VALUES = setOf("true", "yes", "1", "on")
    private val NSFW_MARKERS = setOf(
        "nsfw",
        "adult-content",
        "explicit-content",
        "pornographic",
        "uncensored",
        "r18",
        "18+",
    )
    private val NAME_NSFW_TOKENS = setOf(
        "nsfw",
        "adult",
        "explicit",
        "porn",
        "pornographic",
        "uncensored",
        "r18",
        "18+",
    )
    private val UTILITY_TOKENS = setOf(
        "filter",
        "checker",
        "detector",
        "classifier",
        "safety",
    )
}
