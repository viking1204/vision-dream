package io.github.xororz.localdream.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies that device-only native evidence cannot be forged through model files. */
@RunWith(AndroidJUnit4::class)
class NativeRuntimeAttestationStoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sourceModelId = "attestation-source-${UUID.randomUUID()}"
    private val targetModelId = "attestation-target-${UUID.randomUUID()}"

    @After
    fun removeEvidence() {
        NativeRuntimeAttestationStore.recordFile(context, sourceModelId).delete()
        NativeRuntimeAttestationStore.recordFile(context, targetModelId).delete()
    }

    @Test
    fun appPrivateEvidenceRoundTrips() {
        val expected = attestation()

        NativeRuntimeAttestationStore.write(context, sourceModelId, expected)

        assertEquals(expected, NativeRuntimeAttestationStore.read(context, sourceModelId))
    }

    @Test
    fun modifiedCiphertextIsRejected() {
        NativeRuntimeAttestationStore.write(context, sourceModelId, attestation())
        val evidence = NativeRuntimeAttestationStore.recordFile(context, sourceModelId)
        evidence.writeText(evidence.readText().dropLast(1) + "A")

        assertNull(NativeRuntimeAttestationStore.read(context, sourceModelId))
    }

    @Test
    fun evidenceCopiedToAnotherModelIsRejectedByAuthenticatedModelBinding() {
        NativeRuntimeAttestationStore.write(context, sourceModelId, attestation())
        val source = NativeRuntimeAttestationStore.recordFile(context, sourceModelId)
        val target = NativeRuntimeAttestationStore.recordFile(context, targetModelId)
        check(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true)
        source.copyTo(target, overwrite = true)

        assertNull(NativeRuntimeAttestationStore.read(context, targetModelId))
    }

    private fun attestation() = NativeRuntimeAttestation(
        deviceModel = "PJZ110",
        soc = "SM8750",
        qairtVersion = "2.48.40",
        abi = "arm64-v8a",
        htpTarget = "v79",
        contextFingerprint = "a".repeat(64),
        loadedLibraryFingerprints = mapOf(
            "libQnnHtp.so" to "b".repeat(64),
            "libQnnHtpV79Stub.so" to "c".repeat(64),
        ),
        observedAtEpochMillis = 1L,
    )
}
