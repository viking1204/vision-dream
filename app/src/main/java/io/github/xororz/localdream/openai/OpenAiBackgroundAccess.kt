package io.github.xororz.localdream.openai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Keeps the user in control of the battery exemption required by always-on
 * local API servers on devices that freeze optimized foreground services.
 */
object OpenAiBackgroundAccess {
    fun requiresVendorControl(): Boolean = requiresVendorBackgroundControl(
        Build.MANUFACTURER,
    )

    fun isExempt(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(PowerManager::class.java)
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestExemption(context: Context) {
        val packageUri = Uri.parse("package:${context.packageName}")
        if (requiresVendorControl()) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || isExempt(context)) return
        val directRequest = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            packageUri,
        )
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val intent = if (directRequest.resolveActivity(context.packageManager) != null) {
            directRequest
        } else {
            fallback
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

internal fun requiresVendorBackgroundControl(manufacturer: String): Boolean {
    val normalized = manufacturer.trim().lowercase()
    return normalized == "oneplus" ||
        normalized == "oppo" ||
        normalized == "realme"
}
