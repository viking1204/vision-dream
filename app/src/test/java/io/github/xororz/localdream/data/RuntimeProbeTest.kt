package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeProbeTest {
    @Test
    fun `target device with complete matching evidence is verified`() {
        val probe = RuntimeProbeEvaluator.evaluate(input(nativeReady = true))

        assertEquals(RuntimeProbeStatus.VERIFIED, probe.status)
        assertTrue(probe.rejectionReasons.isEmpty())
    }

    @Test
    fun `non target device and failed native readiness are rejected`() {
        val probe = RuntimeProbeEvaluator.evaluate(input(deviceModel = "Redmi K30", nativeReady = false))

        assertEquals(RuntimeProbeStatus.REJECTED, probe.status)
        assertTrue("DEVICE_MODEL_MISMATCH" in probe.rejectionReasons)
        assertTrue("NATIVE_NOT_READY" in probe.rejectionReasons)
    }

    @Test
    fun `missing evidence is unavailable instead of a performance result`() {
        assertEquals(RuntimeProbeStatus.UNAVAILABLE, RuntimeProbeEvaluator.evaluate(input(soc = null)).status)
    }

    @Test
    fun `process start without native readiness is rejected and missing maps is unavailable`() {
        assertEquals(
            RuntimeProbeStatus.REJECTED,
            RuntimeProbeEvaluator.evaluate(input(nativeReady = false)).status,
        )
        assertEquals(
            RuntimeProbeStatus.UNAVAILABLE,
            RuntimeProbeEvaluator.evaluate(input().copy(loadedLibraryFingerprints = emptyMap())).status,
        )
    }

    @Test
    fun `compatibility fallback cannot be verified for target performance`() {
        val fallback = input().copy(
            compatibility = RuntimeCompatibilityResult(emptySet(), requiresCompatibilityFallback = true),
        )

        val probe = RuntimeProbeEvaluator.evaluate(fallback)

        assertEquals(RuntimeProbeStatus.REJECTED, probe.status)
        assertTrue("COMPATIBILITY_FALLBACK_REQUIRED" in probe.rejectionReasons)
    }

    private fun input(
        deviceModel: String? = "PJZ110",
        soc: String? = "SM8750",
        nativeReady: Boolean? = true,
    ) = RuntimeProbeInput(
        deviceModel = deviceModel,
        soc = soc,
        abi = "arm64-v8a",
        qairtVersion = "2.48.40",
        htpTarget = "v79",
        contextFingerprint = "a".repeat(64),
        loadedLibraryFingerprints = mapOf("libQnnHtpV79.so" to "b".repeat(64)),
        compatibility = RuntimeCompatibilityResult(emptySet(), requiresCompatibilityFallback = false),
        nativeReady = nativeReady,
    )
}
