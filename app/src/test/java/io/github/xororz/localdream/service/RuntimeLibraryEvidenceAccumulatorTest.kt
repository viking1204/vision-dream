package io.github.xororz.localdream.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeLibraryEvidenceAccumulatorTest {
    @Test
    fun retainsTransientMappingsUntilAllRequiredLibrariesWereActuallyObserved() {
        val accumulator = RuntimeLibraryEvidenceAccumulator(
            mapOf(
                "libQnnHtp.so" to "host-digest",
                "libQnnHtpV79Stub.so" to "stub-digest",
            ),
        )

        assertFalse(
            accumulator.observe(mapOf("libQnnHtp.so" to "host-digest"))
                .requiredLibrariesObserved,
        )
        val completed = accumulator.observe(mapOf("libQnnHtpV79Stub.so" to "stub-digest"))

        assertTrue(completed.requiredLibrariesObserved)
        assertEquals(
            mapOf(
                "libQnnHtp.so" to "host-digest",
                "libQnnHtpV79Stub.so" to "stub-digest",
            ),
            completed.loadedLibraryFingerprints,
        )
    }

    @Test
    fun mismatchedMappedLibraryNeverSatisfiesTheExpectedRuntimeContract() {
        val accumulator = RuntimeLibraryEvidenceAccumulator(
            mapOf("libQnnHtpV79Stub.so" to "expected-digest"),
        )

        val snapshot = accumulator.observe(
            mapOf("libQnnHtpV79Stub.so" to "different-digest"),
        )

        assertFalse(snapshot.requiredLibrariesObserved)
        assertEquals(
            "different-digest",
            snapshot.loadedLibraryFingerprints["libQnnHtpV79Stub.so"],
        )
    }
}
