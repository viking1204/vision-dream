package io.github.xororz.localdream.data

import org.json.JSONException
import org.json.JSONObject

/**
 * 性能预设的版本化执行配置。只有严格 v1/v2 能成为新的用户预设或绑定；`{}` 仅用于
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
    /** null means a v1 snapshot and deliberately preserves the old native defaults. */
    val cpuClipThreads: Int? = null,
    val htpPowerMode: HtpPowerMode? = null,
    val htpDynamicPartitioning: HtpDynamicPartitioning? = null,
)

/** QNN DCVS V3 policy exposed by configJson v2. */
enum class HtpPowerMode {
    PERFORMANCE,
    ADJUST_UP_DOWN,
    POWER_SAVER,
}

/** QNN HTP device dynamic-partitioning policy exposed by configJson v2. */
enum class HtpDynamicPartitioning {
    AUTO,
    ENABLED,
    DISABLED,
}

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
 * must carry a strict v1 or v2 engine object.
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
    private const val V1_SCHEMA_VERSION = 1
    private const val V2_SCHEMA_VERSION = 2
    private val ROOT_KEYS = setOf("schemaVersion", "engine")
    private val V1_ENGINE_KEYS = setOf("sdxlLowRam", "animaLowRam", "animaSequentialDit")
    private val V2_ENGINE_KEYS = V1_ENGINE_KEYS + setOf(
        "cpuClipThreads",
        "htpPowerMode",
        "htpDynamicPartitioning",
    )

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
        val schemaVersion = numericVersion.toInt()
        if (schemaVersion !in setOf(V1_SCHEMA_VERSION, V2_SCHEMA_VERSION)) {
            return ParsedPerformancePresetConfig(PresetConfigParseStatus.UNSUPPORTED_VERSION)
        }
        val engine = root.opt("engine") as? JSONObject
            ?: return ParsedPerformancePresetConfig(PresetConfigParseStatus.INVALID)
        return when (schemaVersion) {
            V1_SCHEMA_VERSION -> parseV1(engine)
            V2_SCHEMA_VERSION -> parseV2(engine)
            else -> error("schema version has already been checked")
        }
    }

    /**
     * Serializes the product-facing editor state into the only writable
     * performance-preset schema. Keeping this beside the strict parser avoids
     * Compose, MCP, and future clients each hand-building subtly different
     * JSON payloads.
     */
    fun encodeV2(engine: PerformancePresetEngineConfig): String {
        val cpuClipThreads = requireNotNull(engine.cpuClipThreads) {
            "v2 requires cpuClipThreads"
        }
        require(cpuClipThreads in 1..8) { "cpuClipThreads must be between 1 and 8" }
        val htpPowerMode = requireNotNull(engine.htpPowerMode) {
            "v2 requires htpPowerMode"
        }
        val htpDynamicPartitioning = requireNotNull(engine.htpDynamicPartitioning) {
            "v2 requires htpDynamicPartitioning"
        }
        return JSONObject()
            .put("schemaVersion", V2_SCHEMA_VERSION)
            .put(
                "engine",
                JSONObject()
                    .put("sdxlLowRam", engine.sdxlLowRam)
                    .put("animaLowRam", engine.animaLowRam)
                    .put("animaSequentialDit", engine.animaSequentialDit)
                    .put("cpuClipThreads", cpuClipThreads)
                    .put("htpPowerMode", htpPowerMode.name)
                    .put("htpDynamicPartitioning", htpDynamicPartitioning.name),
            )
            .toString()
    }

    private fun parseV1(engine: JSONObject): ParsedPerformancePresetConfig {
        if (engine.keys().asSequence().toSet() != V1_ENGINE_KEYS) {
            return ParsedPerformancePresetConfig(PresetConfigParseStatus.INVALID)
        }
        val values = V1_ENGINE_KEYS.associateWith { key -> engine.opt(key) }
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

    private fun parseV2(engine: JSONObject): ParsedPerformancePresetConfig {
        if (engine.keys().asSequence().toSet() != V2_ENGINE_KEYS) {
            return ParsedPerformancePresetConfig(PresetConfigParseStatus.INVALID)
        }
        val sdxlLowRam = engine.opt("sdxlLowRam") as? Boolean
            ?: return ParsedPerformancePresetConfig(PresetConfigParseStatus.INVALID)
        val animaLowRam = engine.opt("animaLowRam") as? Boolean
            ?: return ParsedPerformancePresetConfig(PresetConfigParseStatus.INVALID)
        val animaSequentialDit = engine.opt("animaSequentialDit") as? Boolean
            ?: return ParsedPerformancePresetConfig(PresetConfigParseStatus.INVALID)
        val cpuClipThreads = engine.opt("cpuClipThreads")
        if (cpuClipThreads !is Number ||
            cpuClipThreads.toDouble() != cpuClipThreads.toInt().toDouble() ||
            cpuClipThreads.toInt() !in 1..8
        ) {
            return ParsedPerformancePresetConfig(PresetConfigParseStatus.INVALID)
        }
        val htpPowerMode = runCatching {
            HtpPowerMode.valueOf(engine.opt("htpPowerMode") as? String ?: return@runCatching null)
        }.getOrNull() ?: return ParsedPerformancePresetConfig(PresetConfigParseStatus.INVALID)
        val htpDynamicPartitioning = runCatching {
            HtpDynamicPartitioning.valueOf(
                engine.opt("htpDynamicPartitioning") as? String ?: return@runCatching null,
            )
        }.getOrNull() ?: return ParsedPerformancePresetConfig(PresetConfigParseStatus.INVALID)
        return ParsedPerformancePresetConfig(
            status = PresetConfigParseStatus.SUPPORTED,
            engine = PerformancePresetEngineConfig(
                sdxlLowRam = sdxlLowRam,
                animaLowRam = animaLowRam,
                animaSequentialDit = animaSequentialDit,
                cpuClipThreads = cpuClipThreads.toInt(),
                htpPowerMode = htpPowerMode,
                htpDynamicPartitioning = htpDynamicPartitioning,
            ),
        )
    }
}
