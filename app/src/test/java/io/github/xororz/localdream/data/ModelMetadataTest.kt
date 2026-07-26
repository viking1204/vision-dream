package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelMetadataTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun metadataRoundTripsThroughModelDirectory() {
        val directory = temporaryFolder.newFolder("model")
        val expected = ModelMetadata(
            contentRating = ModelContentRating.NSFW,
            ratingSource = ModelRatingSource.REPOSITORY_METADATA,
            ratingEvidence = setOf("tag:nsfw"),
            source = ModelSourceMetadata(
                repositoryId = "owner/model",
                revision = "a".repeat(40),
                artifactKind = "local_dream_directory",
            ),
        )

        ModelMetadataStore.write(directory, expected)

        assertEquals(expected, ModelMetadataStore.read(directory))
    }

    @Test
    fun missingOrUnsupportedMetadataStaysUnknown() {
        val directory = temporaryFolder.newFolder("legacy")
        assertEquals(null, ModelMetadataStore.read(directory))

        directory.resolve(ModelMetadataStore.FILE_NAME).writeText(
            """{"schema_version":2,"content_rating":"nsfw"}""",
        )
        assertEquals(null, ModelMetadataStore.read(directory))
    }

    @Test
    fun legacyNameInferenceRecognizesEmbeddedNsfwWithoutMislabelingUtilities() {
        assertEquals(
            ModelContentRating.NSFW,
            inferLegacyModelContentRating("fabledIllusionNSFW_v7Apoapsis"),
        )
        assertEquals(
            ModelContentRating.UNKNOWN,
            inferLegacyModelContentRating("owner/nsfw-filter"),
        )
    }

    @Test
    fun persistedRatingOverridesLegacyNameInference() {
        val metadata = ModelMetadata(
            contentRating = ModelContentRating.SFW,
            ratingSource = ModelRatingSource.USER,
        )

        assertEquals(
            ModelContentRating.SFW,
            resolveModelContentRating(metadata, "fabledIllusionNSFW_v7Apoapsis"),
        )
    }
}
