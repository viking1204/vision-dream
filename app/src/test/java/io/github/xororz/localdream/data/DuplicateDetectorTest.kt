package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateDetectorTest {

    private val detector = DuplicateDetector()

    private fun installed(
        localModelId: String,
        sourceRepositoryId: String? = null,
        sourceRemoteModelId: String? = null,
        contentSha256: String? = null,
    ) = InstalledModelInfo(
        localModelId = localModelId,
        sourceRepositoryId = sourceRepositoryId,
        sourceRemoteModelId = sourceRemoteModelId,
        contentSha256 = contentSha256,
    )

    @Test
    fun sourceIdMatchWinsOverSha256AndLocalId() {
        val installed = listOf(
            installed(
                localModelId = "dreamshaper",
                sourceRepositoryId = "xororz/sd-qnn",
                sourceRemoteModelId = "DreamShaperV8_qnn2.28_8gen3.zip",
                contentSha256 = "a".repeat(64),
            ),
        )

        val result = detector.detect(
            sourceRepositoryId = "xororz/sd-qnn",
            sourceRemoteModelId = "DreamShaperV8_qnn2.28_8gen3.zip",
            contentSha256 = "b".repeat(64),
            localModelId = "different_id",
            installedModels = installed,
        )

        assertEquals(
            DuplicateDetector.DuplicateResult.SourceIdMatch("dreamshaper"),
            result,
        )
    }

    @Test
    fun contentSha256MatchDetectedWithDifferentLocalId() {
        val sha = "a".repeat(64)
        val installed = listOf(
            installed(
                localModelId = "portrait_model",
                sourceRepositoryId = "owner/portrait",
                sourceRemoteModelId = "portrait.safetensors",
                contentSha256 = sha,
            ),
        )

        val result = detector.detect(
            sourceRepositoryId = "other/repo",
            sourceRemoteModelId = "other-artifact.bin",
            contentSha256 = sha,
            localModelId = "completely_different_id",
            installedModels = installed,
        )

        assertEquals(
            DuplicateDetector.DuplicateResult.ContentSha256Match("portrait_model"),
            result,
        )
    }

    @Test
    fun localIdConflictWhenIdMatchesButSourceAndShaDiffer() {
        val installed = listOf(
            installed(
                localModelId = "dreamshaper",
                sourceRepositoryId = "xororz/sd-qnn",
                sourceRemoteModelId = "DreamShaperV8_qnn2.28_8gen3.zip",
                contentSha256 = "a".repeat(64),
            ),
        )

        val result = detector.detect(
            sourceRepositoryId = "another/repo",
            sourceRemoteModelId = "different-artifact.zip",
            contentSha256 = "b".repeat(64),
            localModelId = "dreamshaper",
            installedModels = installed,
        )

        assertEquals(
            DuplicateDetector.DuplicateResult.LocalIdConflict("dreamshaper"),
            result,
        )
    }

    @Test
    fun noConflictWhenEverythingDiffers() {
        val installed = listOf(
            installed(
                localModelId = "dreamshaper",
                sourceRepositoryId = "xororz/sd-qnn",
                sourceRemoteModelId = "DreamShaperV8_qnn2.28_8gen3.zip",
                contentSha256 = "a".repeat(64),
            ),
        )

        val result = detector.detect(
            sourceRepositoryId = "owner/portrait",
            sourceRemoteModelId = "portrait.safetensors",
            contentSha256 = "b".repeat(64),
            localModelId = "portrait_model",
            installedModels = installed,
        )

        assertEquals(
            DuplicateDetector.DuplicateResult.NoConflict,
            result,
        )
    }

    @Test
    fun sourceIdMatchRequiresBothRepositoryIdAndRemoteIdPresent() {
        val installed = listOf(
            installed(
                localModelId = "dreamshaper",
                sourceRepositoryId = "xororz/sd-qnn",
                sourceRemoteModelId = "DreamShaperV8_qnn2.28_8gen3.zip",
                contentSha256 = "a".repeat(64),
            ),
        )

        // Only repositoryId present on incoming → cannot be a SourceIdMatch.
        val repoOnly = detector.detect(
            sourceRepositoryId = "xororz/sd-qnn",
            sourceRemoteModelId = null,
            contentSha256 = "b".repeat(64),
            localModelId = "new_id",
            installedModels = installed,
        )
        assertEquals(
            DuplicateDetector.DuplicateResult.NoConflict,
            repoOnly,
        )

        // Only remoteModelId present on incoming → cannot be a SourceIdMatch.
        val remoteOnly = detector.detect(
            sourceRepositoryId = null,
            sourceRemoteModelId = "DreamShaperV8_qnn2.28_8gen3.zip",
            contentSha256 = "b".repeat(64),
            localModelId = "new_id",
            installedModels = installed,
        )
        assertEquals(
            DuplicateDetector.DuplicateResult.NoConflict,
            remoteOnly,
        )
    }

    @Test
    fun nullSourceFallsThroughToSha256AndLocalIdChecks() {
        val sha = "a".repeat(64)
        val installed = listOf(
            installed(
                localModelId = "dreamshaper",
                sourceRepositoryId = null,
                sourceRemoteModelId = null,
                contentSha256 = sha,
            ),
        )

        val result = detector.detect(
            sourceRepositoryId = null,
            sourceRemoteModelId = null,
            contentSha256 = sha,
            localModelId = "different_id",
            installedModels = installed,
        )

        assertEquals(
            DuplicateDetector.DuplicateResult.ContentSha256Match("dreamshaper"),
            result,
        )
    }

    @Test
    fun nullSha256FallsThroughToLocalIdCheck() {
        val installed = listOf(
            installed(
                localModelId = "dreamshaper",
                sourceRepositoryId = "xororz/sd-qnn",
                sourceRemoteModelId = "DreamShaperV8_qnn2.28_8gen3.zip",
                contentSha256 = null,
            ),
        )

        val result = detector.detect(
            sourceRepositoryId = "another/repo",
            sourceRemoteModelId = "other.bin",
            contentSha256 = null,
            localModelId = "dreamshaper",
            installedModels = installed,
        )

        assertEquals(
            DuplicateDetector.DuplicateResult.LocalIdConflict("dreamshaper"),
            result,
        )
    }

    @Test
    fun allNullsProduceNoConflict() {
        val installed = listOf(
            installed(
                localModelId = "dreamshaper",
                sourceRepositoryId = "xororz/sd-qnn",
                sourceRemoteModelId = "DreamShaperV8_qnn2.28_8gen3.zip",
                contentSha256 = "a".repeat(64),
            ),
        )

        val result = detector.detect(
            sourceRepositoryId = null,
            sourceRemoteModelId = null,
            contentSha256 = null,
            localModelId = "fresh_id",
            installedModels = installed,
        )

        assertEquals(
            DuplicateDetector.DuplicateResult.NoConflict,
            result,
        )
    }

    @Test
    fun emptyInstalledListIsNoConflict() {
        val result = detector.detect(
            sourceRepositoryId = "owner/repo",
            sourceRemoteModelId = "model.bin",
            contentSha256 = "a".repeat(64),
            localModelId = "fresh_id",
            installedModels = emptyList(),
        )

        assertEquals(
            DuplicateDetector.DuplicateResult.NoConflict,
            result,
        )
    }

    @Test
    fun sourceIdMatchFoundInSecondInstalledModel() {
        val installed = listOf(
            installed(
                localModelId = "first",
                sourceRepositoryId = "owner/first",
                sourceRemoteModelId = "first.bin",
                contentSha256 = "1".repeat(64),
            ),
            installed(
                localModelId = "second",
                sourceRepositoryId = "owner/second",
                sourceRemoteModelId = "second.bin",
                contentSha256 = "2".repeat(64),
            ),
        )

        val result = detector.detect(
            sourceRepositoryId = "owner/second",
            sourceRemoteModelId = "second.bin",
            contentSha256 = "3".repeat(64),
            localModelId = "incoming",
            installedModels = installed,
        )

        assertEquals(
            DuplicateDetector.DuplicateResult.SourceIdMatch("second"),
            result,
        )
    }

    @Test
    fun sha256MatchWinsOverLocalIdWhenSourceDoesNotMatch() {
        val sha = "a".repeat(64)
        val installed = listOf(
            installed(
                localModelId = "same_id",
                sourceRepositoryId = "owner/repo",
                sourceRemoteModelId = "model.bin",
                contentSha256 = sha,
            ),
        )

        val result = detector.detect(
            sourceRepositoryId = "different/repo",
            sourceRemoteModelId = "different.bin",
            contentSha256 = sha,
            localModelId = "same_id",
            installedModels = installed,
        )

        // sha256 match has higher priority than local id conflict.
        assertEquals(
            DuplicateDetector.DuplicateResult.ContentSha256Match("same_id"),
            result,
        )
    }

    @Test
    fun whitespaceOnlyStringsAreTreatedAsNull() {
        val installed = listOf(
            installed(
                localModelId = "dreamshaper",
                sourceRepositoryId = "xororz/sd-qnn",
                sourceRemoteModelId = "DreamShaperV8_qnn2.28_8gen3.zip",
                contentSha256 = "a".repeat(64),
            ),
        )

        val result = detector.detect(
            sourceRepositoryId = "  ",
            sourceRemoteModelId = "\t",
            contentSha256 = "   ",
            localModelId = "  ",
            installedModels = installed,
        )

        assertEquals(
            DuplicateDetector.DuplicateResult.NoConflict,
            result,
        )
    }

    @Test
    fun sourceIdMatchIsCaseSensitive() {
        val installed = listOf(
            installed(
                localModelId = "dreamshaper",
                sourceRepositoryId = "Owner/Repo",
                sourceRemoteModelId = "Model.bin",
                contentSha256 = null,
            ),
        )

        val result = detector.detect(
            sourceRepositoryId = "owner/repo",
            sourceRemoteModelId = "model.bin",
            contentSha256 = null,
            localModelId = "new_id",
            installedModels = installed,
        )

        // Different case → no source match → falls through to NoConflict.
        assertTrue(
            "expected NoConflict but was $result",
            result is DuplicateDetector.DuplicateResult.NoConflict,
        )
    }
}
