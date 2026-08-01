package io.github.xororz.localdream.data

/**
 * Detects duplicate models by source ID, content SHA-256, and local model ID.
 *
 * Detection priority (see [detect]):
 *  1. [DuplicateResult.SourceIdMatch] — same `repositoryId` + `remoteModelId`
 *     pair on an installed model.
 *  2. [DuplicateResult.ContentSha256Match] — same content SHA-256 on an
 *     installed model.
 *  3. [DuplicateResult.LocalIdConflict] — same `localModelId` as an installed
 *     model but no source/sha256 match.
 *  4. [DuplicateResult.NoConflict] — none of the above.
 */
class DuplicateDetector {

    sealed interface DuplicateResult {
        data class SourceIdMatch(val existingModelId: String) : DuplicateResult
        data class ContentSha256Match(val existingModelId: String) : DuplicateResult
        data class LocalIdConflict(val existingModelId: String) : DuplicateResult
        data object NoConflict : DuplicateResult
    }

    fun detect(
        sourceRepositoryId: String?,
        sourceRemoteModelId: String?,
        contentSha256: String?,
        localModelId: String,
        installedModels: List<InstalledModelInfo>,
    ): DuplicateResult {
        val normalizedRepoId = sourceRepositoryId?.trim()?.takeIf(String::isNotEmpty)
        val normalizedRemoteId = sourceRemoteModelId?.trim()?.takeIf(String::isNotEmpty)
        val normalizedSha256 = contentSha256?.trim()?.takeIf(String::isNotEmpty)
        val normalizedLocalId = localModelId.trim().takeIf(String::isNotEmpty)

        if (normalizedRepoId != null && normalizedRemoteId != null) {
            val sourceMatch = installedModels.firstOrNull {
                it.sourceRepositoryId == normalizedRepoId &&
                    it.sourceRemoteModelId == normalizedRemoteId
            }
            if (sourceMatch != null) {
                return DuplicateResult.SourceIdMatch(sourceMatch.localModelId)
            }
        }

        if (normalizedSha256 != null) {
            val sha256Match = installedModels.firstOrNull {
                it.contentSha256 == normalizedSha256
            }
            if (sha256Match != null) {
                return DuplicateResult.ContentSha256Match(sha256Match.localModelId)
            }
        }

        if (normalizedLocalId != null) {
            val localIdMatch = installedModels.firstOrNull {
                it.localModelId == normalizedLocalId
            }
            if (localIdMatch != null) {
                return DuplicateResult.LocalIdConflict(localIdMatch.localModelId)
            }
        }

        return DuplicateResult.NoConflict
    }
}

data class InstalledModelInfo(
    val localModelId: String,
    val sourceRepositoryId: String?,
    val sourceRemoteModelId: String?,
    val contentSha256: String?,
)
