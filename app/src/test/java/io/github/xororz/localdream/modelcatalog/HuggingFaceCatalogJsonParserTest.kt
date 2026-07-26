package io.github.xororz.localdream.modelcatalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HuggingFaceCatalogJsonParserTest {
    @Test
    fun parsesHuggingFaceArrayAndCardMetadata() {
        val repositories = HuggingFaceCatalogJsonParser.parseRepositories(
            """
            [
              {
                "id": "artist/portrait",
                "author": "artist",
                "sha": "0123456789abcdef0123456789abcdef01234567",
                "pipeline_tag": "text-to-image",
                "library_name": "diffusers",
                "tags": ["stable-diffusion", "local-dream"],
                "base_model": "runwayml/stable-diffusion-v1-5",
                "cardData": {
                  "base_model": ["sd-v1-5", "runwayml/stable-diffusion-v1-5"],
                  "format": "safetensors",
                  "formats": ["mnn"],
                  "model_type": "sd15cpu",
                  "custom": {"family": "portrait"}
                },
                "config": {"diffusers": {"_class_name": "StableDiffusionPipeline"}},
                "downloads": 42,
                "likes": "7",
                "lastModified": "2026-07-23T00:00:00.000Z",
                "nsfw": true,
                "private": false,
                "gated": "false",
                "disabled": 0
              }
            ]
            """.trimIndent(),
        )

        val repository = repositories.single()
        assertEquals("artist/portrait", repository.id)
        assertEquals("artist", repository.author)
        assertEquals("0123456789abcdef0123456789abcdef01234567", repository.sha)
        assertEquals("text-to-image", repository.pipelineTag)
        assertEquals("diffusers", repository.libraryName)
        assertEquals("StableDiffusionPipeline", repository.configClassName)
        assertEquals(setOf("stable-diffusion", "local-dream"), repository.tags)
        assertEquals(
            setOf("runwayml/stable-diffusion-v1-5", "sd-v1-5"),
            repository.baseModels,
        )
        assertEquals(setOf("safetensors", "mnn"), repository.formats)
        assertEquals("sd15cpu", repository.modelType)
        assertEquals(setOf("portrait"), repository.cardMetadata["custom"])
        assertEquals(42L, repository.downloads)
        assertEquals(7L, repository.likes)
        assertFalse(repository.isPrivate)
        assertFalse(repository.isGated)
        assertFalse(repository.isDisabled)
        assertTrue(repository.declaredNsfw == true)
    }

    @Test
    fun parsesEachSupportedWrappedResponse() {
        listOf("items", "models", "data").forEach { wrapper ->
            val repositories = HuggingFaceCatalogJsonParser.parseRepositories(
                """{"$wrapper":[{"modelId":"owner/model-$wrapper"}]}""",
            )

            assertEquals("owner/model-$wrapper", repositories.single().id)
        }
    }

    @Test
    fun parsesSiblingLfsShaAndSize() {
        val repository = HuggingFaceCatalogJsonParser.parseRepositories(
            """
            [
              {
                "id": "owner/model",
                "siblings": [
                  {
                    "rfilename": "model.zip",
                    "lfs": {
                      "size": 123456,
                      "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                    }
                  },
                  {
                    "path": "checkpoint.safetensors",
                    "size": "654321",
                    "lfs": {
                      "size": 1,
                      "oid": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                    }
                  }
                ]
              }
            ]
            """.trimIndent(),
        ).single()

        assertEquals(
            HuggingFaceModelFile(
                path = "model.zip",
                sizeBytes = 123456,
                lfsSha256 = "a".repeat(64),
            ),
            repository.files[0],
        )
        assertEquals(
            HuggingFaceModelFile(
                path = "checkpoint.safetensors",
                sizeBytes = 654321,
                lfsSha256 = "b".repeat(64),
            ),
            repository.files[1],
        )
    }

    @Test
    fun skipsNonObjectEntriesAndRepositoriesWithoutIds() {
        val repositories = HuggingFaceCatalogJsonParser.parseRepositories(
            """[null, "unexpected", {}, {"id":"valid/model"}]""",
        )

        assertEquals(listOf("valid/model"), repositories.map { it.id })
        assertTrue(repositories.single().files.isEmpty())
    }
}
