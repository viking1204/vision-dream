package io.github.xororz.localdream.modelcatalog

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCatalogSearchResultTest {
    @Test
    fun installationKeepsDisplayNameSeparateFromCanonicalModelId() {
        val result = ModelCatalogSearchResult(
            repositoryId = "author/portrait-model",
            localModelId = "portrait_model",
            displayName = "Portrait Model",
            artifactFileName = "model.zip",
            downloadUrl = "https://example.test/model.zip",
            artifactKind = CatalogArtifactKind.LOCAL_DREAM_ZIP,
            backendHint = CatalogBackendHint.PREPACKAGED,
            backendType = "sd15cpu",
            hardwareTarget = null,
            sizeBytes = null,
            lastModified = null,
        )

        assertEquals("portrait_model", result.localModelId)
        assertEquals("Portrait Model", result.installationMetadata().displayName)
    }
}
