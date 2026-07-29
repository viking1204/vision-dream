package io.github.xororz.localdream.service

/**
 * Retains transient native-library mappings observed across one backend
 * process. Low-RAM inference loads and unloads UNet/VAE QNN libraries by
 * phase, so no single `/proc/<pid>/maps` snapshot is guaranteed to contain
 * every library that actually participated in a completed generation.
 */
internal class RuntimeLibraryEvidenceAccumulator(
    private val requiredLibraryFingerprints: Map<String, String>,
) {
    private val observed = linkedMapOf<String, String>()

    fun observe(mappedLibraryFingerprints: Map<String, String>): RuntimeLibraryEvidenceSnapshot {
        observed.putAll(mappedLibraryFingerprints)
        return RuntimeLibraryEvidenceSnapshot(
            loadedLibraryFingerprints = observed.toSortedMap(),
            requiredLibrariesObserved = requiredLibraryFingerprints.isNotEmpty() &&
                requiredLibraryFingerprints.all { (name, digest) -> observed[name] == digest },
        )
    }
}

internal data class RuntimeLibraryEvidenceSnapshot(
    val loadedLibraryFingerprints: Map<String, String>,
    val requiredLibrariesObserved: Boolean,
)
