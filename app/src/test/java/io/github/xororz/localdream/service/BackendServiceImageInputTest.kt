package io.github.xororz.localdream.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendServiceImageInputTest {
    @Test
    fun openAiExplicitImageInputIgnoresDisabledLocalPreference() {
        assertTrue(
            BackendService.resolveImageInputRequested(
                requestOwner = BackendService.REQUEST_OWNER_OPENAI_API,
                explicitOverride = true,
                localPreference = false,
            ),
        )
    }

    @Test
    fun openAiRequestWithoutExplicitCapabilityFailsClosed() {
        assertFalse(
            BackendService.resolveImageInputRequested(
                requestOwner = BackendService.REQUEST_OWNER_OPENAI_API,
                explicitOverride = null,
                localPreference = true,
            ),
        )
    }

    @Test
    fun localCommandStillUsesImg2imgPreferenceWithoutOverride() {
        assertFalse(
            BackendService.resolveImageInputRequested(
                requestOwner = null,
                explicitOverride = null,
                localPreference = false,
            ),
        )
        assertTrue(
            BackendService.resolveImageInputRequested(
                requestOwner = null,
                explicitOverride = null,
                localPreference = true,
            ),
        )
    }
}
