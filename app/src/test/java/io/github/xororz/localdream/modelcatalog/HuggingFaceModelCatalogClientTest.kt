package io.github.xororz.localdream.modelcatalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HuggingFaceModelCatalogClientTest {
    @Test
    fun searchUrlPreservesBaseUrlSubpathAndEncodesQuery() {
        val client = HuggingFaceModelCatalogClient("https://models.example.test/hugging-face")

        val url = client.buildSearchUrl("portrait / anime", 12)

        assertEquals("/hugging-face/api/models", url.encodedPath)
        assertEquals("portrait / anime", url.queryParameter("search"))
        assertEquals("12", url.queryParameter("limit"))
        assertEquals("true", url.queryParameter("full"))
        assertEquals("true", url.queryParameter("config"))
        assertEquals("true", url.queryParameter("cardData"))
    }

    @Test
    fun downloadUrlPreservesSubpathAndUsesSafeRevision() {
        val client = HuggingFaceModelCatalogClient("https://models.example.test/hub/")

        val url = client.downloadUrl(
            artifact(
                repositoryId = "owner/model",
                repositorySha = "0123456789abcdef0123456789abcdef01234567",
                path = "portrait model.zip",
            ),
        )

        assertEquals(
            "https://models.example.test/hub/owner/model/resolve/" +
                "0123456789abcdef0123456789abcdef01234567/portrait%20model.zip",
            url,
        )
    }

    @Test
    fun downloadUrlFallsBackToMainForUnsafeRevision() {
        val client = HuggingFaceModelCatalogClient("https://models.example.test")

        val url = client.downloadUrl(
            artifact(
                repositoryId = "owner/model",
                repositorySha = "refs/heads/main",
                path = "model.zip",
            ),
        )

        assertEquals(
            "https://models.example.test/owner/model/resolve/main/model.zip",
            url,
        )
    }

    @Test
    fun downloadUrlRejectsUnsafeRepositoryIds() {
        val client = HuggingFaceModelCatalogClient("https://models.example.test")

        listOf(
            "../model",
            "owner/../model",
            "owner/model/extra",
            "owner%2Fother/model",
            "owner/model?download=true",
        ).forEach { repositoryId ->
            assertThrows(IllegalArgumentException::class.java) {
                client.downloadUrl(artifact(repositoryId = repositoryId))
            }
        }
    }

    @Test
    fun downloadUrlRejectsUnsafeArtifactPaths() {
        val client = HuggingFaceModelCatalogClient("https://models.example.test")

        listOf(
            "../model.zip",
            "nested/model.zip",
            """nested\model.zip""",
            ".",
            "..",
            "model\u0000.zip",
            "model\n.zip",
        ).forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                client.downloadUrl(artifact(path = path))
            }
        }
    }

    @Test
    fun directoryDownloadUrlAllowsSafeNestedPathAtPinnedRevision() {
        val client = HuggingFaceModelCatalogClient("https://models.example.test/hub/")
        val revision = "a".repeat(40)

        val url = client.directoryDownloadUrl(
            repositoryId = "owner/model",
            revision = revision,
            sourcePath = "portrait model/unet.bin",
        )

        assertEquals(
            "https://models.example.test/hub/owner/model/resolve/$revision/" +
                "portrait%20model/unet.bin",
            url,
        )
    }

    @Test
    fun directoryDownloadUrlRejectsUnpinnedOrUnsafePath() {
        val client = HuggingFaceModelCatalogClient("https://models.example.test")

        assertThrows(IllegalArgumentException::class.java) {
            client.directoryDownloadUrl("owner/model", "main", "model/unet.bin")
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.directoryDownloadUrl("owner/model", "a".repeat(40), "../unet.bin")
        }
    }

    @Test
    fun baseUrlRejectsNonHttpAndAmbiguousComponents() {
        listOf(
            "file:///tmp/models",
            "https://models.example.test/hub?token=secret",
            "https://models.example.test/hub#section",
        ).forEach { baseUrl ->
            assertThrows(IllegalArgumentException::class.java) {
                HuggingFaceModelCatalogClient(baseUrl)
            }
        }
    }

    private fun artifact(
        repositoryId: String = "owner/model",
        repositorySha: String? = null,
        path: String = "model.zip",
    ): CompatibleModelArtifact = CompatibleModelArtifact(
        repositoryId = repositoryId,
        repositorySha = repositorySha,
        file = HuggingFaceModelFile(path),
        localModelId = "model",
        displayName = "Model",
        kind = CatalogArtifactKind.LOCAL_DREAM_ZIP,
        backendHint = CatalogBackendHint.PREPACKAGED,
        backendType = "sd15cpu",
    )
}
