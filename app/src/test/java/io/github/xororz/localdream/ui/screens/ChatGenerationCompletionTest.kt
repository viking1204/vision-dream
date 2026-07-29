package io.github.xororz.localdream.ui.screens

import io.github.xororz.localdream.service.NativeRuntimeAttestationRecorder
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ChatGenerationCompletionTest {
    @Test
    fun `attestation callback runs only after Chat native generation returns`() {
        val events = mutableListOf<String>()

        val result = completeChatNativeGeneration(
            generate = {
                events += "generated"
                "image"
            },
            onNativeGenerationSuccess = { events += "attested" },
        )

        assertEquals("image", result)
        assertEquals(listOf("generated", "attested"), events)
    }

    @Test
    fun `attestation callback does not run when Chat native generation fails or is cancelled`() {
        listOf<Throwable>(IllegalStateException("failed"), CancellationException("cancelled")).forEach { failure ->
            var attestations = 0

            try {
                completeChatNativeGeneration<String>(
                    generate = { throw failure },
                    onNativeGenerationSuccess = { attestations += 1 },
                )
                fail("expected generation failure")
            } catch (actual: Throwable) {
                assertEquals(failure, actual)
            }
            assertEquals(0, attestations)
        }
    }

    @Test
    fun `attestation persistence failure does not discard a completed Chat image`() {
        val image = completeChatNativeGeneration(
            generate = { "image" },
            onNativeGenerationSuccess = {
                NativeRuntimeAttestationRecorder.persistNonFatal(
                    write = { throw IllegalStateException("keystore unavailable") },
                    onPersisted = {},
                )
            },
        )

        assertEquals("image", image)
    }
}
