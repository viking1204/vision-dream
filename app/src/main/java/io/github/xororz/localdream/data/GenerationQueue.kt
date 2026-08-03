package io.github.xororz.localdream.data

import org.json.JSONArray
import org.json.JSONObject

/** Lifecycle of a queued generation run as shown in the queue panel. */
enum class GenerationTaskStatus {
    QUEUED,
    RUNNING,
    ;

    companion object {
        fun fromKey(key: String): GenerationTaskStatus = entries.firstOrNull { it.name == key } ?: QUEUED
    }
}

/**
 * One generation run waiting for the single native inference slot.
 *
 * This is the persistable projection of a submitted request: everything the
 * queue panel renders and everything needed to replay the run after process
 * death. Source image bytes are deliberately absent — they are large and are
 * re-selected by the user, mirroring the [CreationDraft] decision.
 */
data class GenerationTask(
    val id: String,
    val modelId: String,
    val modelName: String,
    val prompt: String,
    val negativePrompt: String = "",
    val mode: String = "TXT2IMG",
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 20,
    val cfg: Float = 7f,
    val seed: Long? = null,
    val scheduler: String = "dpm",
    val status: GenerationTaskStatus = GenerationTaskStatus.QUEUED,
    val createdAt: Long = 0L,
) {
    /** Image-based modes cannot be restored without their source bytes. */
    val needsSourceImage: Boolean get() = mode != "TXT2IMG"
}

/**
 * JSON envelope for the pending queue.
 *
 * A DataStore string keeps the queue out of Room entirely: no new entity, no
 * schema bump, no migration. The database stays at v10.
 */
object GenerationQueueCodec {
    private const val KEY_ID = "id"
    private const val KEY_MODEL_ID = "modelId"
    private const val KEY_MODEL_NAME = "modelName"
    private const val KEY_PROMPT = "prompt"
    private const val KEY_NEGATIVE_PROMPT = "negativePrompt"
    private const val KEY_MODE = "mode"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"
    private const val KEY_STEPS = "steps"
    private const val KEY_CFG = "cfg"
    private const val KEY_SEED = "seed"
    private const val KEY_SCHEDULER = "scheduler"
    private const val KEY_STATUS = "status"
    private const val KEY_CREATED_AT = "createdAt"

    /**
     * Serialises the restorable part of the queue. Tasks that depend on a
     * source image are dropped: replaying them after a restart would silently
     * generate from missing input.
     */
    fun toJson(tasks: List<GenerationTask>): String {
        val array = JSONArray()
        tasks.filterNot { it.needsSourceImage }.forEach { task ->
            array.put(
                JSONObject().apply {
                    put(KEY_ID, task.id)
                    put(KEY_MODEL_ID, task.modelId)
                    put(KEY_MODEL_NAME, task.modelName)
                    put(KEY_PROMPT, task.prompt)
                    put(KEY_NEGATIVE_PROMPT, task.negativePrompt)
                    put(KEY_MODE, task.mode)
                    put(KEY_WIDTH, task.width)
                    put(KEY_HEIGHT, task.height)
                    put(KEY_STEPS, task.steps)
                    put(KEY_CFG, task.cfg.toDouble())
                    task.seed?.let { put(KEY_SEED, it) }
                    put(KEY_SCHEDULER, task.scheduler)
                    put(KEY_STATUS, task.status.name)
                    put(KEY_CREATED_AT, task.createdAt)
                },
            )
        }
        return array.toString()
    }

    /** Returns an empty list for blank or corrupt payloads. */
    fun fromJson(raw: String): List<GenerationTask> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val id = json.optString(KEY_ID).takeIf { it.isNotEmpty() } ?: continue
                val modelId = json.optString(KEY_MODEL_ID).takeIf { it.isNotEmpty() } ?: continue
                add(
                    GenerationTask(
                        id = id,
                        modelId = modelId,
                        modelName = json.optString(KEY_MODEL_NAME, modelId),
                        prompt = json.optString(KEY_PROMPT, ""),
                        negativePrompt = json.optString(KEY_NEGATIVE_PROMPT, ""),
                        mode = json.optString(KEY_MODE, "TXT2IMG"),
                        width = json.optInt(KEY_WIDTH, 512),
                        height = json.optInt(KEY_HEIGHT, 512),
                        steps = json.optInt(KEY_STEPS, 20),
                        cfg = json.optDouble(KEY_CFG, 7.0).toFloat(),
                        seed = if (json.has(KEY_SEED)) json.optLong(KEY_SEED) else null,
                        scheduler = json.optString(KEY_SCHEDULER, "dpm"),
                        status = GenerationTaskStatus.fromKey(json.optString(KEY_STATUS)),
                        createdAt = json.optLong(KEY_CREATED_AT, 0L),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
}

/**
 * Ordering helpers for the queue panel.
 *
 * Model switches dominate the cost of a batch: the native backend tears down
 * and reloads weights whenever consecutive runs disagree on the model. Smart
 * sort therefore clusters same-model tasks while keeping the queue honest
 * about who was submitted first.
 */
object GenerationQueueSorter {
    /**
     * Clusters tasks by model, stably.
     *
     * Groups appear in the order their first task was submitted, and inside a
     * group the original relative order is preserved. A running task always
     * stays at the head — it already owns the inference lease and reordering
     * it would be a lie.
     */
    fun clusterByModel(tasks: List<GenerationTask>): List<GenerationTask> {
        val running = tasks.filter { it.status == GenerationTaskStatus.RUNNING }
        val queued = tasks.filter { it.status != GenerationTaskStatus.RUNNING }
        val grouped = LinkedHashMap<String, MutableList<GenerationTask>>()
        queued.forEach { task ->
            grouped.getOrPut(task.modelId) { mutableListOf() } += task
        }
        return running + grouped.values.flatten()
    }

    /** Number of distinct model loads the given order will trigger. */
    fun modelSwitchCount(tasks: List<GenerationTask>): Int {
        var switches = 0
        var previous: String? = null
        tasks.forEach { task ->
            if (previous != null && previous != task.modelId) switches++
            previous = task.modelId
        }
        return switches
    }
}
