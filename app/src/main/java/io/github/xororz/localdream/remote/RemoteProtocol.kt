package io.github.xororz.localdream.remote

import io.github.xororz.localdream.data.PerformancePresetConfig
import io.github.xororz.localdream.data.PerformancePresetEngineConfig
import io.github.xororz.localdream.data.PresetConfigParseStatus
import io.github.xororz.localdream.data.PresetSnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire protocol between a controller device (B) and a host device (A).
 *
 * The host exposes two ports:
 *  - CONTROL_PORT: a small authenticated JSON API served by [RemoteHostServer]
 *    (model catalog, model activation, backend status).
 *  - GENERATION_PORT: the native backend itself, started with --listen_all
 *    while host mode is active. The controller talks to /generate, /tokenize
 *    and /health on it directly, reusing the exact same client code paths as
 *    local generation.
 */
object RemoteProtocol {
    const val CONTROL_PORT = 8808
    const val GENERATION_PORT = 8081
    const val PROTOCOL_VERSION = 1
    const val APP_ID = "localdream"

    const val PATH_INFO = "/info"
    const val PATH_MODELS = "/models"
    const val PATH_SELECT = "/select"
    const val PATH_STATUS = "/status"
    const val PATH_STOP = "/stop"

    // Pseudo model id accepted by /select: starts the host's native backend
    // in --upscaler_mode (standalone upscaling, no diffusion model).
    const val UPSCALER_MODEL_ID = "__upscaler__"

    // Backend states reported by /status.
    const val STATE_IDLE = "idle"
    const val STATE_STARTING = "starting"
    const val STATE_RUNNING = "running"
    const val STATE_ERROR = "error"
}

/**
 * The immutable performance-preset value accepted by a controller before it
 * asks a remote host to start a generation backend. The host intentionally
 * does not look up its own mutable preset rows: a v1/v2 preset must match its
 * serialized engine flags, while the legacy fallback carries the controller's
 * already-resolved flags explicitly.
 */
data class RemotePresetExecution(
    val snapshot: PresetSnapshot,
    val engineConfig: PerformancePresetEngineConfig,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(
            "preset_snapshot",
            JSONObject().apply {
                put("preset_id", snapshot.presetId)
                put("name", snapshot.name)
                put("selector", snapshot.selector)
                put("config_json", snapshot.configJson)
                put("revision", snapshot.revision)
            },
        )
        put(
            "engine",
            JSONObject().apply {
                put("sdxl_low_ram", engineConfig.sdxlLowRam)
                put("anima_low_ram", engineConfig.animaLowRam)
                put("anima_sequential_dit", engineConfig.animaSequentialDit)
                engineConfig.cpuClipThreads?.let { put("cpu_clip_threads", it) }
                engineConfig.htpPowerMode?.let { put("htp_power_mode", it.name) }
                engineConfig.htpDynamicPartitioning?.let {
                    put("htp_dynamic_partitioning", it.name)
                }
            },
        )
    }

    companion object {
        fun fromJson(body: JSONObject): RemotePresetExecution? {
            val snapshotJson = body.optJSONObject("preset_snapshot") ?: return null
            val engineJson = body.optJSONObject("engine") ?: return null
            if (snapshotJson.keys().asSequence().toSet() != SNAPSHOT_KEYS) {
                return null
            }
            val presetId = snapshotJson.opt("preset_id") as? String ?: return null
            val name = snapshotJson.opt("name") as? String ?: return null
            val selector = snapshotJson.opt("selector") as? String ?: return null
            val configJson = snapshotJson.opt("config_json") as? String ?: return null
            val revision = snapshotJson.opt("revision") as? Number ?: return null
            if (presetId.isBlank() || name.isBlank() || selector.isBlank() ||
                revision.toDouble() != revision.toLong().toDouble()
            ) {
                return null
            }
            val parsed = PerformancePresetConfig.parse(configJson)
            if (parsed.status != PresetConfigParseStatus.SUPPORTED &&
                parsed.status != PresetConfigParseStatus.LEGACY_COMPATIBILITY
            ) {
                return null
            }
            val expectedEngineKeys = when {
                parsed.engine == null -> V1_ENGINE_KEYS
                parsed.engine.cpuClipThreads == null -> V1_ENGINE_KEYS
                else -> V2_ENGINE_KEYS
            }
            if (engineJson.keys().asSequence().toSet() != expectedEngineKeys) return null
            val sdxlLowRam = engineJson.opt("sdxl_low_ram") as? Boolean ?: return null
            val animaLowRam = engineJson.opt("anima_low_ram") as? Boolean ?: return null
            val animaSequentialDit = engineJson.opt("anima_sequential_dit") as? Boolean ?: return null
            val engineConfig = PerformancePresetEngineConfig(
                sdxlLowRam = sdxlLowRam,
                animaLowRam = animaLowRam,
                animaSequentialDit = animaSequentialDit,
                cpuClipThreads = engineJson.optionalInteger("cpu_clip_threads"),
                htpPowerMode = engineJson.optionalEnum("htp_power_mode") {
                    io.github.xororz.localdream.data.HtpPowerMode.valueOf(it)
                },
                htpDynamicPartitioning = engineJson.optionalEnum("htp_dynamic_partitioning") {
                    io.github.xororz.localdream.data.HtpDynamicPartitioning.valueOf(it)
                },
            )
            if (parsed.engine != null && parsed.engine != engineConfig) return null
            return RemotePresetExecution(
                snapshot = PresetSnapshot(
                    presetId = presetId,
                    name = name,
                    selector = selector,
                    configJson = configJson,
                    revision = revision.toLong(),
                ),
                engineConfig = engineConfig,
            )
        }

        private val SNAPSHOT_KEYS = setOf("preset_id", "name", "selector", "config_json", "revision")
        private val V1_ENGINE_KEYS = setOf("sdxl_low_ram", "anima_low_ram", "anima_sequential_dit")
        private val V2_ENGINE_KEYS = V1_ENGINE_KEYS + setOf(
            "cpu_clip_threads",
            "htp_power_mode",
            "htp_dynamic_partitioning",
        )

        private fun JSONObject.optionalInteger(key: String): Int? {
            if (!has(key)) return null
            val value = opt(key) as? Number ?: return null
            if (value.toDouble() != value.toInt().toDouble() || value.toInt() !in 1..8) return null
            return value.toInt()
        }

        private fun <T> JSONObject.optionalEnum(
            key: String,
            parser: (String) -> T,
        ): T? {
            if (!has(key)) return null
            val value = opt(key) as? String ?: return null
            return runCatching { parser(value) }.getOrNull()
        }
    }
}

/** Identity block returned by GET /info (the only unauthenticated endpoint). */
data class RemoteHostInfo(
    val protocol: Int,
    val version: String,
    val deviceName: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("app", RemoteProtocol.APP_ID)
        put("protocol", protocol)
        put("version", version)
        put("device", deviceName)
    }

    companion object {
        fun fromJson(json: JSONObject): RemoteHostInfo? {
            if (json.optString("app") != RemoteProtocol.APP_ID) return null
            return RemoteHostInfo(
                protocol = json.optInt("protocol", 0),
                version = json.optString("version"),
                deviceName = json.optString("device"),
            )
        }
    }
}

/** Per-model resolved default parameters shipped in the catalog. */
data class RemoteModelDefaults(
    val prompt: String,
    val negativePrompt: String,
    val steps: Float,
    val cfg: Float,
    val scheduler: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("prompt", prompt)
        put("negative_prompt", negativePrompt)
        put("steps", steps.toDouble())
        put("cfg", cfg.toDouble())
        put("scheduler", scheduler)
    }

    companion object {
        fun fromJson(json: JSONObject): RemoteModelDefaults = RemoteModelDefaults(
            prompt = json.optString("prompt"),
            negativePrompt = json.optString("negative_prompt"),
            steps = json.optDouble("steps", 20.0).toFloat(),
            cfg = json.optDouble("cfg", 7.0).toFloat(),
            scheduler = json.optString("scheduler", "dpm"),
        )
    }
}

/** One installed model on the host, as listed by GET /models. */
data class RemoteModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val runOnCpu: Boolean,
    val isSdxl: Boolean,
    val isAnima: Boolean,
    val isCustom: Boolean,
    val generationSize: Int,
    val defaults: RemoteModelDefaults,
    // Width/height pairs usable by this model on the host (SD1.5 NPU patch
    // resolutions). Empty for models with a free or fixed canvas.
    val resolutions: List<Pair<Int, Int>>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("run_on_cpu", runOnCpu)
        put("is_sdxl", isSdxl)
        put("is_anima", isAnima)
        put("is_custom", isCustom)
        put("generation_size", generationSize)
        put("defaults", defaults.toJson())
        put(
            "resolutions",
            JSONArray().apply {
                resolutions.forEach { (w, h) ->
                    put(
                        JSONArray().apply {
                            put(w)
                            put(h)
                        },
                    )
                }
            },
        )
    }

    companion object {
        fun fromJson(json: JSONObject): RemoteModelInfo? {
            val id = json.optString("id")
            if (id.isEmpty()) return null
            val resolutions = mutableListOf<Pair<Int, Int>>()
            val array = json.optJSONArray("resolutions") ?: JSONArray()
            for (i in 0 until array.length()) {
                val pair = array.optJSONArray(i) ?: continue
                val w = pair.optInt(0)
                val h = pair.optInt(1)
                if (w > 0 && h > 0) resolutions.add(Pair(w, h))
            }
            return RemoteModelInfo(
                id = id,
                name = json.optString("name", id),
                description = json.optString("description"),
                runOnCpu = json.optBoolean("run_on_cpu", false),
                isSdxl = json.optBoolean("is_sdxl", false),
                isAnima = json.optBoolean("is_anima", false),
                isCustom = json.optBoolean("is_custom", false),
                generationSize = json.optInt("generation_size", 512),
                defaults = RemoteModelDefaults.fromJson(
                    json.optJSONObject("defaults") ?: JSONObject(),
                ),
                resolutions = resolutions,
            )
        }
    }
}

/**
 * One installed upscaler on the host. [path] is the weight file's absolute
 * path on the HOST's filesystem: the native /upscale endpoint loads the model
 * from the X-Upscaler-Path header, so the controller echoes this path back
 * when requesting an upscale on the host.
 */
data class RemoteUpscalerInfo(
    val id: String,
    val path: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("path", path)
    }

    companion object {
        fun fromJson(json: JSONObject): RemoteUpscalerInfo? {
            val id = json.optString("id")
            val path = json.optString("path")
            if (id.isEmpty() || path.isEmpty()) return null
            return RemoteUpscalerInfo(id, path)
        }
    }
}

/** Full catalog returned by GET /models. */
data class RemoteCatalog(
    // Whether the host backend is started with img2img support; gates the
    // controller's img2img/inpaint/aspect-ratio UI exactly like the local pref.
    val useImg2img: Boolean,
    val models: List<RemoteModelInfo>,
    val upscalers: List<RemoteUpscalerInfo>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("use_img2img", useImg2img)
        put("models", JSONArray().apply { models.forEach { put(it.toJson()) } })
        put("upscalers", JSONArray().apply { upscalers.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JSONObject): RemoteCatalog {
            val models = mutableListOf<RemoteModelInfo>()
            val array = json.optJSONArray("models") ?: JSONArray()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                RemoteModelInfo.fromJson(obj)?.let { models.add(it) }
            }
            val upscalers = mutableListOf<RemoteUpscalerInfo>()
            val upscalerArray = json.optJSONArray("upscalers") ?: JSONArray()
            for (i in 0 until upscalerArray.length()) {
                val obj = upscalerArray.optJSONObject(i) ?: continue
                RemoteUpscalerInfo.fromJson(obj)?.let { upscalers.add(it) }
            }
            return RemoteCatalog(
                useImg2img = json.optBoolean("use_img2img", true),
                models = models,
                upscalers = upscalers,
            )
        }
    }
}

/** Backend state snapshot returned by GET /status. */
data class RemoteHostStatus(
    val servingModelId: String?,
    val state: String,
    val message: String?,
    // Model id an error pertains to, or null for a model-agnostic error;
    // mirrors BackendService.BackendState.Error.modelId semantics.
    val errorModelId: String?,
    // Resolution the backend was started for. The controller requires a full
    // (model, width, height) match before declaring Ready, so a process still
    // serving the same model at an older resolution is never mistaken for it.
    val width: Int?,
    val height: Int?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("serving_model_id", servingModelId ?: JSONObject.NULL)
        put("state", state)
        put("message", message ?: JSONObject.NULL)
        put("error_model_id", errorModelId ?: JSONObject.NULL)
        put("width", width ?: JSONObject.NULL)
        put("height", height ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): RemoteHostStatus = RemoteHostStatus(
            servingModelId = json.optString("serving_model_id").takeIf {
                it.isNotEmpty() && !json.isNull("serving_model_id")
            },
            state = json.optString("state", RemoteProtocol.STATE_IDLE),
            message = json.optString("message").takeIf {
                it.isNotEmpty() && !json.isNull("message")
            },
            errorModelId = json.optString("error_model_id").takeIf {
                it.isNotEmpty() && !json.isNull("error_model_id")
            },
            width = json.optInt("width").takeIf { it > 0 && !json.isNull("width") },
            height = json.optInt("height").takeIf { it > 0 && !json.isNull("height") },
        )
    }
}
