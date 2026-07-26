package io.github.xororz.localdream.modelcatalog

import io.github.xororz.localdream.data.ModelContentRating
import io.github.xororz.localdream.data.ModelRatingSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelContentClassifierTest {
    @Test
    fun explicitRepositoryTagIsNsfw() {
        val result = ModelContentClassifier.classify(
            HuggingFaceModelRepository(
                id = "owner/model",
                tags = setOf("nsfw"),
            ),
        )

        assertEquals(ModelContentRating.NSFW, result.rating)
        assertEquals(ModelRatingSource.REPOSITORY_METADATA, result.source)
    }

    @Test
    fun utilityRepositoryNameDoesNotCauseFalsePositive() {
        val result = ModelContentClassifier.classify(
            HuggingFaceModelRepository(id = "owner/nsfw-filter"),
        )

        assertEquals(ModelContentRating.UNKNOWN, result.rating)
    }

    @Test
    fun explicitFalseSuppressesNameOnlyHeuristic() {
        val result = ModelContentClassifier.classify(
            HuggingFaceModelRepository(
                id = "owner/uncensored-model",
                declaredNsfw = false,
            ),
        )

        assertEquals(ModelContentRating.UNKNOWN, result.rating)
    }

    @Test
    fun embeddedNsfwInRepositoryNameIsRecognized() {
        val result = ModelContentClassifier.classify(
            HuggingFaceModelRepository(id = "owner/fabledIllusionNSFW_v7Apoapsis"),
        )

        assertEquals(ModelContentRating.NSFW, result.rating)
        assertEquals(ModelRatingSource.REPOSITORY_NAME, result.source)
    }
}
