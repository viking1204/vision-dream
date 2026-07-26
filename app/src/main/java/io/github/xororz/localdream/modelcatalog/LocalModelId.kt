package io.github.xororz.localdream.modelcatalog

import java.text.Normalizer
import java.util.Locale

/**
 * Produces directory-safe model identifiers. The accepted output alphabet is
 * intentionally narrower than Android filenames so remote names cannot smuggle
 * path separators, control characters, or visually ambiguous punctuation into
 * the app's model directory.
 */
object LocalModelId {
    const val MAX_LENGTH = 64

    private val repositorySegment = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
    private val combiningMark = Regex("\\p{M}+")
    private val unsafeRun = Regex("[^a-z0-9]+")
    private val qnnSuffix =
        Regex("(?i)_qnn\\d+(?:\\.\\d+)?_(?:min|8gen[1-9]|8sgen[1-9]|8elite)$")
    private val genericArtifactNames = setOf(
        "model",
        "checkpoint",
        "weights",
        "diffusion_model",
        "diffusion_pytorch_model",
    )
    private val windowsReserved = buildSet {
        addAll(setOf("con", "prn", "aux", "nul"))
        for (number in 1..9) {
            add("com$number")
            add("lpt$number")
        }
    }

    /**
     * Builds an ID from a root-level artifact. Returns null when either remote
     * identifier is malformed or the normalized result is forbidden.
     */
    fun fromArtifact(
        repositoryId: String,
        artifactPath: String,
        forbiddenIds: Set<String> = emptySet(),
    ): String? {
        val repositoryName = validatedRepositoryName(repositoryId) ?: return null
        if (!isSafeRootFile(artifactPath)) return null

        val artifactStem = artifactPath.substringBeforeLast('.', artifactPath)
            .replace(qnnSuffix, "")
        val preferredName = if (artifactStem.lowercase(Locale.ROOT) in genericArtifactNames) {
            repositoryName
        } else {
            artifactStem
        }
        return normalize(preferredName, forbiddenIds)
    }

    /**
     * Builds an ID for a repository-backed directory artifact.
     */
    fun fromDirectory(
        repositoryId: String,
        directoryName: String?,
        forbiddenIds: Set<String> = emptySet(),
    ): String? {
        val repositoryName = validatedRepositoryName(repositoryId) ?: return null
        val preferredName = directoryName?.takeIf(String::isNotBlank) ?: repositoryName
        return normalize(preferredName, forbiddenIds)
    }

    /**
     * Normalizes a user-visible name without ever interpreting it as a path.
     */
    fun normalize(rawName: String, forbiddenIds: Set<String> = emptySet()): String? {
        val containsPathMarker = rawName == "." || ".." in rawName
        val containsUnsafeCharacter =
            rawName.any { it.isISOControl() || it == '/' || it == '\\' }
        if (rawName.isBlank() || containsPathMarker || containsUnsafeCharacter) {
            return null
        }

        val ascii = Normalizer.normalize(rawName, Normalizer.Form.NFKD)
            .replace(combiningMark, "")
            .lowercase(Locale.ROOT)
        val normalized = ascii
            .replace(unsafeRun, "_")
            .trim('_')
            .take(MAX_LENGTH)
            .trimEnd('_')

        if (
            normalized.isBlank() ||
            normalized in windowsReserved ||
            normalized in forbiddenIds.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
        ) {
            return null
        }
        return normalized
    }

    private fun validatedRepositoryName(repositoryId: String): String? {
        if (repositoryId.isBlank() || repositoryId.length > 193 || repositoryId.any { it.isISOControl() }) {
            return null
        }
        val segments = repositoryId.split('/')
        if (segments.size !in 1..2 || segments.any { !repositorySegment.matches(it) || it == "." || it == ".." }) {
            return null
        }
        return segments.last()
    }

    private fun isSafeRootFile(path: String): Boolean = path.isNotBlank() &&
        path != "." &&
        path != ".." &&
        '/' !in path &&
        '\\' !in path &&
        path.none { it.isISOControl() }
}
