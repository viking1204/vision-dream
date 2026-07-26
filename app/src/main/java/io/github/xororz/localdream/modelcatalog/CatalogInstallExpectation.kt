package io.github.xororz.localdream.modelcatalog

import android.os.Build
import androidx.annotation.RequiresApi
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.data.ModelFileLayouts
import java.util.Locale
import org.json.JSONObject

/**
 * Compatibility decision made from repository metadata before an archive is
 * downloaded. Installation re-validates this decision against both the
 * extracted files and the current device instead of trusting the ZIP name.
 */
data class CatalogInstallExpectation(
    val backendType: String,
    val hardwareTarget: String?,
) {
    init {
        val layout = requireNotNull(ModelFileLayouts.forBackend(backendType)) {
            "Unsupported catalog backend"
        }
        val normalizedTarget = hardwareTarget?.lowercase(Locale.ROOT)
        require(normalizedTarget == hardwareTarget) { "Hardware target must be normalized" }
        require(hardwareTarget == null || HARDWARE_TARGET.matches(hardwareTarget)) {
            "Unsupported hardware target"
        }
        require(layout.requiresHardwareTarget == (hardwareTarget != null)) {
            "Catalog backend and hardware target are inconsistent"
        }
    }

    fun toJsonString(): String = JSONObject().apply {
        put(KEY_BACKEND_TYPE, backendType)
        hardwareTarget?.let { put(KEY_HARDWARE_TARGET, it) }
    }.toString()

    internal fun validateDetectedBackend(actualBackendType: String): String? = if (
        actualBackendType == backendType
    ) {
        null
    } else {
        "Archive backend does not match the catalog expectation"
    }

    companion object {
        private const val KEY_BACKEND_TYPE = "backend_type"
        private const val KEY_HARDWARE_TARGET = "hardware_target"
        private const val MAX_JSON_LENGTH = 1024
        private val HARDWARE_TARGET =
            Regex("(?:min|8gen[1-9]|8sgen[1-9]|8elite)")

        fun fromJsonString(rawJson: String): CatalogInstallExpectation {
            require(rawJson.length <= MAX_JSON_LENGTH) {
                "Catalog install expectation is too large"
            }
            val json = JSONObject(rawJson)
            return CatalogInstallExpectation(
                backendType = json.getString(KEY_BACKEND_TYPE),
                hardwareTarget = json.optString(KEY_HARDWARE_TARGET)
                    .trim()
                    .takeIf(String::isNotEmpty),
            )
        }
    }
}

internal data class CatalogDeviceProfile(
    val sdkInt: Int,
    val deviceSupported: Boolean,
    val chipsetSuffix: String?,
    val supportsLargeModels: Boolean,
)

/** Single compatibility rule shared by search presentation and installation. */
object CatalogDeviceCompatibility {
    fun isCurrentDeviceCompatible(expectation: CatalogInstallExpectation): Boolean {
        val sdkInt = Build.VERSION.SDK_INT
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            currentSocModel()
        } else {
            ""
        }
        return isCompatible(
            expectation,
            CatalogDeviceProfile(
                sdkInt = sdkInt,
                deviceSupported = Model.isDeviceSupported(),
                chipsetSuffix = if (sdkInt >= Build.VERSION_CODES.S) {
                    Model.getChipsetSuffix(socModel)
                } else {
                    null
                },
                supportsLargeModels = socModel in LARGE_MODEL_SOCS,
            ),
        )
    }

    internal fun isCompatible(
        expectation: CatalogInstallExpectation,
        profile: CatalogDeviceProfile,
    ): Boolean = when (expectation.backendType) {
        "sd15cpu" -> true

        "sd15npu" -> profile.deviceSupported && targetMatches(expectation, profile)

        "sdxl",
        "anima",
        -> profile.deviceSupported &&
            profile.sdkInt >= Build.VERSION_CODES.S &&
            profile.supportsLargeModels &&
            targetMatches(expectation, profile)

        else -> false
    }

    private fun targetMatches(
        expectation: CatalogInstallExpectation,
        profile: CatalogDeviceProfile,
    ): Boolean {
        if (expectation.hardwareTarget == "min") return true
        return profile.sdkInt >= Build.VERSION_CODES.S &&
            expectation.hardwareTarget == profile.chipsetSuffix
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun currentSocModel(): String = Build.SOC_MODEL

    private val LARGE_MODEL_SOCS = setOf(
        "SM8650",
        "SM8650P",
        "SM8750",
        "SM8750P",
        "SM8845",
        "SM8850",
        "SM8850P",
    )
}
