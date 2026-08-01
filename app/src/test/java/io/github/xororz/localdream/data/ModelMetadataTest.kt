package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            runtimeCompatibility = ModelRuntimeCompatibility(
                qairtVersion = "2.48.40",
                abi = "arm64-v8a",
                htpTarget = "v79",
                contextFingerprint = "a".repeat(64),
            ),
            displayName = "写实人像 XL",
            description = "风格：写实摄影；适合：人像、电影感场景。",
        )

        ModelMetadataStore.write(directory, expected)

        assertEquals(expected, ModelMetadataStore.read(directory))
    }

    @Test
    fun forgedPublicAttestationIsIgnored() {
        val parsed = ModelMetadata.fromJsonString(
            """{"schema_version":3,"content_rating":"sfw","native_runtime_attestation":{"device_model":"PJZ110"}}""",
        )

        assertEquals(ModelContentRating.SFW, parsed.contentRating)
        assertFalse(parsed.toJsonString().contains("native_runtime_attestation"))
    }

    @Test
    fun missingOrUnsupportedMetadataStaysUnknown() {
        val directory = temporaryFolder.newFolder("legacy")
        assertEquals(null, ModelMetadataStore.read(directory))

        directory.resolve(ModelMetadataStore.FILE_NAME).writeText(
            """{"schema_version":6,"content_rating":"nsfw"}""",
        )
        assertEquals(null, ModelMetadataStore.read(directory))
    }

    @Test
    fun v1MetadataRemainsReadableWithUnknownRuntimeCompatibility() {
        val metadata = ModelMetadata.fromJsonString(
            """{"schema_version":1,"content_rating":"sfw"}""",
        )

        assertEquals(ModelContentRating.SFW, metadata.contentRating)
        assertEquals(null, metadata.runtimeCompatibility)
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

    @Test
    fun contentSha256RoundTripsThroughJson() {
        val expected = ModelMetadata(
            contentRating = ModelContentRating.SFW,
            contentSha256 = "a".repeat(64),
        )

        val parsed = ModelMetadata.fromJsonString(expected.toJsonString())

        assertEquals(expected, parsed)
        assertEquals("a".repeat(64), parsed.contentSha256)
    }

    @Test
    fun legacyMetadataWithoutContentSha256ReadsAsNull() {
        val parsed = ModelMetadata.fromJsonString(
            """{"schema_version":4,"content_rating":"sfw"}""",
        )

        assertEquals(ModelContentRating.SFW, parsed.contentRating)
        assertEquals(null, parsed.contentSha256)
        // Re-serializing should not emit content_sha256 when null.
        assertFalse(parsed.toJsonString().contains("content_sha256"))
    }
}
