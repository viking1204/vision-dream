package io.github.xororz.localdream.data

import android.app.ActivityManager
import android.content.Context

/**
 * Device RAM probe shared by the performance-preset resolver and the preset
 * console UI so both agree on what "high memory" means for choosing the
 * out-of-box default. No special permission is required for
 * [ActivityManager.getMemoryInfo].
 */
object DeviceMemory {
    /** Devices with at least this much RAM get the resident (non-lowram) default. */
    const val HIGH_MEMORY_DEVICE_BYTES: Long = 16L * 1024 * 1024 * 1024 // 16 GiB

    fun totalBytes(context: Context): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0L
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.totalMem
    }

    fun isHighMemoryDevice(context: Context): Boolean = totalBytes(context) >= HIGH_MEMORY_DEVICE_BYTES
}
