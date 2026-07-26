package io.github.xororz.localdream.modelcatalog

import io.github.xororz.localdream.data.ModelFileLayouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogDownloadManifestTest {
    @Test
    fun manifestRoundTripsWithCompleteDirectory() {
        val manifest = manifest()

        assertEquals(manifest, CatalogDownloadManifest.fromJsonString(manifest.toJsonString()))
        assertEquals(21L, manifest.declaredTotalBytes)
    }

    @Test
    fun duplicateOrUnsafeTargetsAreRejected() {
        val files = files().toMutableList()
        files += files.first().copy(sourcePath = "other/${files.first().targetName}")

        assertThrows(IllegalArgumentException::class.java) {
            manifest(files)
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest(
                files().map {
                    if (it.targetName == "unet.mnn") {
                        it.copy(sourcePath = "../unet.mnn")
                    } else {
                        it
                    }
                },
            )
        }
    }

    private fun manifest(
        files: List<CatalogDownloadFile> = files(),
    ): CatalogDownloadManifest = CatalogDownloadManifest(
        repositoryId = "owner/model",
        revision = "a".repeat(40),
        backendType = "sd15cpu",
        files = files,
    )

    private fun files(): List<CatalogDownloadFile> = ModelFileLayouts.sd15Cpu.requiredFiles.sorted().mapIndexed { index, name ->
        CatalogDownloadFile(
            sourcePath = "model/$name",
            targetName = name,
            downloadUrl = "https://example.test/owner/model/resolve/${"a".repeat(40)}/model/$name",
            sizeBytes = index.toLong() + 1L,
            sha256 = "b".repeat(64),
        )
    }
}
