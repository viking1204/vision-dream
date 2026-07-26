package io.github.xororz.localdream.data

import android.content.Context
import androidx.core.content.edit

/**
 * Persists the activity-lock preference.
 *
 * Generation services deliberately do not read this preference: locking the
 * visible app must not interrupt API or foreground image generation.
 */
class AppSecurityPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun isBiometricLockEnabled(): Boolean = preferences.getBoolean(
        KEY_BIOMETRIC_LOCK,
        false,
    )

    fun setBiometricLockEnabled(enabled: Boolean) {
        preferences.edit {
            putBoolean(KEY_BIOMETRIC_LOCK, enabled)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "security_preferences"
        const val KEY_BIOMETRIC_LOCK = "biometric_lock"
    }
}
