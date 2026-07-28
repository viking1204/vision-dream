package io.github.xororz.localdream.data

import org.json.JSONException
import org.json.JSONObject

/**
 * 性能预设的版本化执行配置。只有严格 v1 能成为新的用户预设或绑定；`{}` 仅用于
 * 读取旧版本 compatibility fallback 和已受理 Job 的历史快照。
 */
enum class PresetConfigParseStatus {
    SUPPORTED,
    LEGACY_COMPATIBILITY,
    UNSUPPORTED_VERSION,
    INVALID,
}

data class PerformancePresetEngineConfig(
    val sdxlLowRam: Boolean,
    val animaLowRam: Boolean,
    val animaSequentialDit: Boolean,
)

data class ParsedPerformancePresetConfig(
    val status: PresetConfigParseStatus,
    val engine: PerformancePresetEngineConfig? = null,
) {
    val isSupported: Boolean
        get() = status == PresetConfigParseStatus.SUPPORTED
}

/**
 * Converts an accepted immutable snapshot into the exact launch override.
 * The historical `{}` compatibility fallback deliberately has no override: it
 * keeps the established conservative backend defaults, while every new preset
 * must carry a strict v1 engine object.
 */
fun ParsedPerformancePresetConfig.requireExecutableSnapshot(
    isCompatibilityFallback: Boolean,
): PerformancePresetEngineConfig? {
    require(
        isSupported ||
            (isCompatibilityFallback && status == PresetConfigParseStatus.LEGACY_COMPATIBILITY),
    ) { "Preset config is not executable" }
    return engine
}

object PerformancePresetConfig {
    private const val SCHEMA_VERSION = 1
    private val ROOT_KEYS = setOf("schemaVersion", "engine")
    private val ENGINE_KEYS = setOf("sdxlLowRam", "animaLowRam", "animaSequentialDit")

    fun parse(configJson: String): ParsedPerformancePresetConfig {
        val root = try {
            JSONObject(configJson)
        } catch (_: JSONException) {
            return ParsedPerformancePresetConfig(PresetConfigParseStatus.INVALID)
        }
        if (root.length() == 0 && configJson == "{}") {
            return ParsedPerformancePresetConfig(PresetConfigParseStatus.LEGACY_COMPATIBILITY)
        }
        if (root.keys().asSequence().toSet() != ROOT_KEYS || !root.has("schemaVersion") || !root.has("engine")) {
            return ParsedPerformancePresetConfig(PresetConfigParseStatus.INVALID)
        }
        val version = root.opt("schemaVersion")
        if (version !is Number) {
            return ParsedPerformancePresetConfig(PresetConfigParseStatus.INVALID)
        }
        val numericVersion = version.toDouble()
        if (!numericVersion.isFinite() || numericVersion != numericVersion.toInt().toDouble()) {
            return ParsedPerformancePresetConfig(PresetConfigParseStatus.INVALID)
        }
        if (numericVersion.toInt() != SCHEMA_VERSION) {
            return ParsedPerformancePresetConfig(PresetConfigParseStatus.UNSUPPORTED_VERSION)
        }
        val engine = root.opt("engine") as? JSONObject
            ?: return ParsedPerformancePresetConfig(PresetConfigParseStatus.INVALID)
        if (engine.keys().asSequence().toSet() != ENGINE_KEYS) {
            return ParsedPerformancePresetConfig(PresetConfigParseStatus.INVALID)
        }
        val values = ENGINE_KEYS.associateWith { key -> engine.opt(key) }
        if (values.values.any { it !is Boolean }) {
            return ParsedPerformancePresetConfig(PresetConfigParseStatus.INVALID)
        }
        return ParsedPerformancePresetConfig(
            status = PresetConfigParseStatus.SUPPORTED,
            engine = PerformancePresetEngineConfig(
                sdxlLowRam = values.getValue("sdxlLowRam") as Boolean,
                animaLowRam = values.getValue("animaLowRam") as Boolean,
                animaSequentialDit = values.getValue("animaSequentialDit") as Boolean,
            ),
        )
    }
}
