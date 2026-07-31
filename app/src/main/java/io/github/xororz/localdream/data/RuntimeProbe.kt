package io.github.xororz.localdream.data

/**
 * Device-side evidence collected for a performance run.
 *
 * A packaged manifest proves only what the APK contains. This probe keeps the
 * observed device identity, runtime contract and native startup outcome
 * together so a host harness can reject non-target measurements.
 */
enum class RuntimeProbeStatus {
    VERIFIED,
    REJECTED,
    UNAVAILABLE,
}

data class RuntimeProbe(
    val status: RuntimeProbeStatus,
    val deviceModel: String? = null,
    val soc: String? = null,
    val abi: String? = null,
    val qairtVersion: String? = null,
    val htpTarget: String? = null,
    val contextFingerprint: String? = null,
    val loadedLibraryFingerprints: Map<String, String> = emptyMap(),
    val nativeReady: Boolean? = null,
    val rejectionReasons: Set<String> = emptySet(),
)

/**
 * Authenticated diagnostic evidence used by the local acceptance harness.
 *
 * It keeps the observed runtime contract (including library digests) but never
 * exposes device paths, native command lines, model files or credentials.
 */
data class RuntimeProbeProjection(
    val status: RuntimeProbeStatus,
    val rejectionReasons: List<String>,
    val deviceModel: String? = null,
    val soc: String? = null,
    val abi: String? = null,
    val qairtVersion: String? = null,
    val htpTarget: String? = null,
    val contextFingerprint: String? = null,
    val loadedLibraryFingerprints: Map<String, String> = emptyMap(),
    val nativeReady: Boolean? = null,
)

fun RuntimeProbe.toProtectedProjection(): RuntimeProbeProjection = RuntimeProbeProjection(
    status = status,
    rejectionReasons = rejectionReasons.sorted(),
    deviceModel = deviceModel,
    soc = soc,
    abi = abi,
    qairtVersion = qairtVersion,
    htpTarget = htpTarget,
    contextFingerprint = contextFingerprint,
    loadedLibraryFingerprints = loadedLibraryFingerprints.toSortedMap(),
    nativeReady = nativeReady,
)

data class RuntimeProbeInput(
    val deviceModel: String?,
    val soc: String?,
    val abi: String?,
    val qairtVersion: String?,
    val htpTarget: String?,
    val contextFingerprint: String?,
    val loadedLibraryFingerprints: Map<String, String>,
    /** Digests for the V79 host libraries that must be mapped by this child. */
    val requiredV79LibraryFingerprints: Map<String, String>,
    val compatibility: RuntimeCompatibilityResult,
    /** True only after the launched native process answered its readiness probe. */
    val nativeReady: Boolean?,
)

object RuntimeProbeEvaluator {
    internal const val TARGET_MODEL = "PJZ110"
    internal const val TARGET_SOC = "SM8750"
    internal const val TARGET_QAIRT = "2.48.40"
    internal const val TARGET_HTP = "v79"

    fun evaluate(input: RuntimeProbeInput): RuntimeProbe {
        val unavailable = listOf(
            input.deviceModel,
            input.soc,
            input.abi,
            input.qairtVersion,
            input.htpTarget,
            input.contextFingerprint,
        ).any { it.isNullOrBlank() } || input.loadedLibraryFingerprints.isEmpty() ||
            input.requiredV79LibraryFingerprints.isEmpty() || input.nativeReady == null
        if (unavailable) return probe(input, RuntimeProbeStatus.UNAVAILABLE, emptySet())

        val reasons = linkedSetOf<String>()
        reasons += input.compatibility.rejections.map(RuntimeCompatibilityRejection::name)
        if (input.compatibility.requiresCompatibilityFallback) {
            reasons += "COMPATIBILITY_FALLBACK_REQUIRED"
        }
        if (input.deviceModel != TARGET_MODEL) reasons += "DEVICE_MODEL_MISMATCH"
        if (!input.soc.equals(TARGET_SOC, ignoreCase = true)) reasons += "SOC_MISMATCH"
        if (input.qairtVersion != TARGET_QAIRT) reasons += RuntimeCompatibilityRejection.QAIRT_VERSION_MISMATCH.name
        if (!input.htpTarget.equals(TARGET_HTP, ignoreCase = true)) reasons += RuntimeCompatibilityRejection.HTP_TARGET_MISMATCH.name
        if (input.requiredV79LibraryFingerprints.any { (name, digest) ->
                input.loadedLibraryFingerprints[name] != digest
            }
        ) {
            reasons += "HTP_V79_LIBRARY_MAPPING_MISMATCH"
        }
        if (input.nativeReady != true) reasons += "NATIVE_NOT_READY"
        return probe(
            input,
            if (reasons.isEmpty()) RuntimeProbeStatus.VERIFIED else RuntimeProbeStatus.REJECTED,
            reasons,
        )
    }

    private fun probe(
        input: RuntimeProbeInput,
        status: RuntimeProbeStatus,
        reasons: Set<String>,
    ) = RuntimeProbe(
        status = status,
        deviceModel = input.deviceModel,
        soc = input.soc,
        abi = input.abi,
        qairtVersion = input.qairtVersion,
        htpTarget = input.htpTarget,
        contextFingerprint = input.contextFingerprint,
        loadedLibraryFingerprints = input.loadedLibraryFingerprints.toSortedMap(),
        nativeReady = input.nativeReady,
        rejectionReasons = reasons,
    )

    /**
     * Returns a target contract only from evidence observed on the running
     * native process. A caller must still observe a completed inference before
     * persisting the contract as an attestation.
     */
    internal fun targetCompatibility(probe: RuntimeProbe): ModelRuntimeCompatibility? {
        val abi = probe.abi ?: return null
        val htpTarget = probe.htpTarget ?: return null
        val contextFingerprint = probe.contextFingerprint ?: return null
        val targetMatches = probe.deviceModel == TARGET_MODEL &&
            probe.soc.equals(TARGET_SOC, ignoreCase = true)
        val runtimeMatches = probe.qairtVersion == TARGET_QAIRT &&
            htpTarget.equals(TARGET_HTP, ignoreCase = true)
        val evidenceComplete = abi.isNotBlank() &&
            contextFingerprint.isNotBlank() &&
            probe.loadedLibraryFingerprints.filterKeys(REQUIRED_V79_LIBRARY_NAMES::contains).size ==
            REQUIRED_V79_LIBRARY_NAMES.size &&
            probe.nativeReady == true
        if (!targetMatches || !runtimeMatches || !evidenceComplete) return null
        return ModelRuntimeCompatibility(
            qairtVersion = probe.qairtVersion,
            abi = abi,
            htpTarget = htpTarget,
            contextFingerprint = contextFingerprint,
        )
    }

    internal fun requiredV79Libraries(files: List<RuntimeManifestFile>): Map<String, String> = files.filter { it.name in REQUIRED_V79_LIBRARY_NAMES }.associate { it.name to it.sha256 }

    private val REQUIRED_V79_LIBRARY_NAMES = setOf(
        "libQnnHtp.so",
        "libQnnHtpV79Stub.so",
    )
}
