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
 * The authenticated HTTP and MCP diagnostic shape.  It deliberately excludes
 * the loaded-library map: even hashes become an unnecessary asset inventory
 * outside the device-local report bundle.
 */
data class RuntimeProbeProjection(
    val status: RuntimeProbeStatus,
    val rejectionReasons: List<String>,
)

fun RuntimeProbe.toProtectedProjection(): RuntimeProbeProjection = RuntimeProbeProjection(
    status = status,
    rejectionReasons = rejectionReasons.sorted(),
)

data class RuntimeProbeInput(
    val deviceModel: String?,
    val soc: String?,
    val abi: String?,
    val qairtVersion: String?,
    val htpTarget: String?,
    val contextFingerprint: String?,
    val loadedLibraryFingerprints: Map<String, String>,
    val compatibility: RuntimeCompatibilityResult,
    /** True only after the launched native process answered its readiness probe. */
    val nativeReady: Boolean?,
)

object RuntimeProbeEvaluator {
    private const val TARGET_MODEL = "PJZ110"
    private const val TARGET_SOC = "SM8750"
    private const val TARGET_QAIRT = "2.48.40"
    private const val TARGET_HTP = "v79"

    fun evaluate(input: RuntimeProbeInput): RuntimeProbe {
        val unavailable = listOf(
            input.deviceModel,
            input.soc,
            input.abi,
            input.qairtVersion,
            input.htpTarget,
            input.contextFingerprint,
        ).any { it.isNullOrBlank() } || input.loadedLibraryFingerprints.isEmpty() || input.nativeReady == null
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
}
