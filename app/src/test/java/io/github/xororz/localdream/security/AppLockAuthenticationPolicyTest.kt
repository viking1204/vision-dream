package io.github.xororz.localdream.security

import androidx.biometric.BiometricManager
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLockAuthenticationPolicyTest {
    @Test
    fun successfulCapabilityCheckIsReady() {
        assertEquals(
            AppLockAuthenticationReadiness.READY,
            AppLockAuthenticationPolicy.fromCanAuthenticateResult(
                BiometricManager.BIOMETRIC_SUCCESS,
            ),
        )
    }

    @Test
    fun unknownCapabilityStillAllowsSystemPromptToDecide() {
        assertEquals(
            AppLockAuthenticationReadiness.UNKNOWN,
            AppLockAuthenticationPolicy.fromCanAuthenticateResult(
                BiometricManager.BIOMETRIC_STATUS_UNKNOWN,
            ),
        )
    }

    @Test
    fun missingEnrollmentIsUnavailable() {
        assertEquals(
            AppLockAuthenticationReadiness.UNAVAILABLE,
            AppLockAuthenticationPolicy.fromCanAuthenticateResult(
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            ),
        )
    }

    @Test
    fun legacyDeviceCredentialRecoversMissingStrongBiometric() {
        assertEquals(
            AppLockAuthenticationReadiness.READY,
            AppLockAuthenticationPolicy.forLegacyDevice(
                strongBiometricResult = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
                deviceCredentialAvailable = true,
            ),
        )
    }

    @Test
    fun legacyDeviceWithoutCredentialUsesStrongBiometricResult() {
        assertEquals(
            AppLockAuthenticationReadiness.UNAVAILABLE,
            AppLockAuthenticationPolicy.forLegacyDevice(
                strongBiometricResult = BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                deviceCredentialAvailable = false,
            ),
        )
    }
}
