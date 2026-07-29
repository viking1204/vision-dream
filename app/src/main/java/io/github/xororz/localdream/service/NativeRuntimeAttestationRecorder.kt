package io.github.xororz.localdream.service

import android.content.Context
import android.util.Log
import io.github.xororz.localdream.data.NativeRuntimeAttestationStore
import io.github.xororz.localdream.data.NativeRuntimeAttestor
import io.github.xororz.localdream.data.RuntimeProbeStatus

/**
 * Persists runtime evidence only after a completed native generation for the
 * model actually served by the process.
 */
internal object NativeRuntimeAttestationRecorder {
    fun record(
        context: Context,
        modelId: String,
    ): Boolean {
        if (BackendService.servingModelId.value != modelId) return false
        val probe = BackendService.runtimeProbe.value
        val attested = NativeRuntimeAttestor.attest(
            probe = probe,
            observedAtEpochMillis = System.currentTimeMillis(),
        ) ?: return false
        return persistNonFatal(
            write = { NativeRuntimeAttestationStore.write(context, modelId, attested) },
            onPersisted = {
                BackendServiceStateHolder.runtimeProbe.value = probe.copy(
                    status = RuntimeProbeStatus.VERIFIED,
                    rejectionReasons = emptySet(),
                )
            },
        )
    }

    /**
     * Evidence is supplementary to a returned native image. A storage or
     * Keystore fault must leave the generation successful but must not upgrade
     * the probe to VERIFIED without durable evidence.
     */
    internal fun persistNonFatal(
        write: () -> Unit,
        onPersisted: () -> Unit,
    ): Boolean = try {
        write()
        onPersisted()
        true
    } catch (failure: Exception) {
        // Logging itself can be unavailable in JVM tests or a damaged runtime;
        // neither that nor evidence persistence may alter image success.
        runCatching { Log.w(TAG, "Runtime attestation persistence failed after native generation", failure) }
        false
    }

    private const val TAG = "NativeRuntimeAttestation"
}
