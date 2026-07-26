package io.github.xororz.localdream.security

import androidx.biometric.BiometricManager

internal enum class AppLockAuthenticationReadiness {
    READY,
    UNKNOWN,
    UNAVAILABLE,
}

/**
 * Converts platform capability checks into the app-lock decisions used by the activity.
 *
 * Keeping this mapping free of Activity state makes the fail-open recovery rule testable:
 * an already-enabled optional app lock must not become permanent after every allowed
 * authenticator is removed from the device.
 */
internal object AppLockAuthenticationPolicy {
    fun fromCanAuthenticateResult(result: Int): AppLockAuthenticationReadiness = when (result) {
        BiometricManager.BIOMETRIC_SUCCESS -> AppLockAuthenticationReadiness.READY
        BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> AppLockAuthenticationReadiness.UNKNOWN
        else -> AppLockAuthenticationReadiness.UNAVAILABLE
    }

    fun forLegacyDevice(
        strongBiometricResult: Int,
        deviceCredentialAvailable: Boolean,
    ): AppLockAuthenticationReadiness {
        if (deviceCredentialAvailable) return AppLockAuthenticationReadiness.READY
        return fromCanAuthenticateResult(strongBiometricResult)
    }
}
