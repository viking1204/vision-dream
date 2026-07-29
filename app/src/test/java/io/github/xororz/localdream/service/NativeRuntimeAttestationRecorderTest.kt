package io.github.xororz.localdream.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRuntimeAttestationRecorderTest {
    @Test
    fun `persistence failure does not escape or mark probe verified`() {
        var verifiedUpdates = 0

        val persisted = NativeRuntimeAttestationRecorder.persistNonFatal(
            write = { throw IllegalStateException("keystore unavailable") },
            onPersisted = { verifiedUpdates += 1 },
        )

        assertFalse(persisted)
        assertEquals(0, verifiedUpdates)
    }

    @Test
    fun `successful persistence updates probe exactly once`() {
        var writes = 0
        var verifiedUpdates = 0

        val persisted = NativeRuntimeAttestationRecorder.persistNonFatal(
            write = { writes += 1 },
            onPersisted = { verifiedUpdates += 1 },
        )

        assertTrue(persisted)
        assertEquals(1, writes)
        assertEquals(1, verifiedUpdates)
    }
}
