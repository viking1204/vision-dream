package io.github.xororz.localdream.modelcatalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelIdTest {
    @Test
    fun directoryUsesFolderOrRepositoryName() {
        assertEquals(
            "portrait_model",
            LocalModelId.fromDirectory("owner/repository", "Portrait Model"),
        )
        assertEquals(
            "repository",
            LocalModelId.fromDirectory("owner/repository", null),
        )
    }

    @Test
    fun qnnArtifactRemovesRuntimeSuffix() {
        assertEquals(
            "dreamshaperv8",
            LocalModelId.fromArtifact(
                repositoryId = "xororz/sd-qnn",
                artifactPath = "DreamShaperV8_qnn2.28_8gen3.zip",
            ),
        )
    }

    @Test
    fun genericCheckpointUsesRepositoryName() {
        assertEquals(
            "beautiful_model",
            LocalModelId.fromArtifact(
                repositoryId = "author/Beautiful-Model",
                artifactPath = "model.safetensors",
            ),
        )
    }

    @Test
    fun normalizationProducesNarrowSafeAlphabet() {
        val result = LocalModelId.normalize("  Café Model (v1.0)  ")

        assertEquals("cafe_model_v1_0", result)
        assertTrue(result!!.matches(Regex("[a-z0-9_]{1,64}")))
    }

    @Test
    fun traversalAndControlCharactersAreRejected() {
        assertNull(LocalModelId.fromArtifact("author/model", "../escape.zip"))
        assertNull(LocalModelId.fromArtifact("author/model", "nested/escape.zip"))
        assertNull(LocalModelId.fromArtifact("author/model", "nested\\escape.zip"))
        assertNull(LocalModelId.normalize("../installed-model"))
        assertNull(LocalModelId.normalize("installed/model"))
        assertNull(LocalModelId.normalize("installed\\model"))
        assertNull(LocalModelId.normalize("model..backup"))
        assertNull(LocalModelId.normalize("unsafe\u0000name"))
    }

    @Test
    fun reservedAndForbiddenIdsAreRejected() {
        assertNull(LocalModelId.normalize("CON"))
        assertNull(LocalModelId.normalize("anythingv5", setOf("anythingv5")))
    }

    @Test
    fun resultIsBoundedToFilesystemLimit() {
        val result = LocalModelId.normalize("a".repeat(100))

        assertEquals(LocalModelId.MAX_LENGTH, result?.length)
    }
}
