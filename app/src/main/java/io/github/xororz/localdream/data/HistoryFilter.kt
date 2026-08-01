package io.github.xororz.localdream.data

import androidx.compose.runtime.Immutable
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import org.json.JSONArray
import org.json.JSONObject

enum class GenerationMode {
    TXT2IMG,
    IMG2IMG,
    INPAINT,
    ULTRAFIX,
    UNKNOWN,
    ;

    companion object {
        fun fromString(s: String?): GenerationMode = when (s) {
            "TXT2IMG" -> TXT2IMG
            "IMG2IMG" -> IMG2IMG
            "INPAINT" -> INPAINT
            "ULTRAFIX" -> ULTRAFIX
            else -> UNKNOWN
        }
    }
}

enum class DeviceFilter { NPU, CPU, GPU }

enum class FavoriteFilter { FAVORITE, NOT_FAVORITE }

@Immutable
data class HistoryFilter(
    val modelIds: Set<String>? = null,
    val modes: Set<GenerationMode>? = null,
    val from: Long? = null,
    val to: Long? = null,
    val sizes: Set<String>? = null,
    val schedulers: Set<String>? = null,
    val devices: Set<DeviceFilter>? = null,
    val promptSubstring: String? = null,
    val favorites: Set<FavoriteFilter>? = null,
    val descending: Boolean = true,
) {
    // Full rows, newest/oldest first. Used by paged and one-shot list queries.
    fun toSqlQuery(): SupportSQLiteQuery = buildQuery(projection = "*", ordered = true)

    // Just the ids matching the filter, in display order. Used by select-all so
    // the selection covers every match, not only the pages loaded in memory.
    fun toIdQuery(): SupportSQLiteQuery = buildQuery(projection = "id", ordered = true)

    // Row count matching the filter. Used to drive the select-all toggle and the
    // selection counter without materializing the list.
    fun toCountQuery(): SupportSQLiteQuery = buildQuery(projection = "COUNT(*)", ordered = false)

    // Newest matches first, capped at [limit]. Backs the result-page thumbnail
    // strip and the seed-on-open effect without loading the whole history.
    fun toRecentQuery(limit: Int): SupportSQLiteQuery = buildQuery(projection = "*", ordered = true, limit = limit)

    private fun buildQuery(
        projection: String,
        ordered: Boolean,
        limit: Int? = null,
    ): SupportSQLiteQuery {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()

        if (!modelIds.isNullOrEmpty()) {
            where += "modelId IN (${modelIds.joinToString(",") { "?" }})"
            args.addAll(modelIds)
        }
        if (!modes.isNullOrEmpty()) {
            // Selecting TXT2IMG also matches UNKNOWN: legacy migrated rows have no mode
            // recorded, and from the user's perspective anything that's not img2img/inpaint
            // is effectively txt2img.
            val expanded =
                if (GenerationMode.TXT2IMG in modes) modes + GenerationMode.UNKNOWN else modes
            where += "mode IN (${expanded.joinToString(",") { "?" }})"
            args.addAll(expanded.map { it.name })
        }
        if (from != null) {
            where += "timestamp >= ?"
            args += from
        }
        if (to != null) {
            where += "timestamp <= ?"
            args += to
        }
        if (!sizes.isNullOrEmpty()) {
            where += "(width || 'x' || height) IN (${sizes.joinToString(",") { "?" }})"
            args.addAll(sizes)
        }
        if (!schedulers.isNullOrEmpty()) {
            where += "scheduler IN (${schedulers.joinToString(",") { "?" }})"
            args.addAll(schedulers)
        }
        if (!devices.isNullOrEmpty()) {
            val parts = mutableListOf<String>()
            // runOnCpu=false → NPU; runOnCpu=true && useOpenCL=false → CPU; runOnCpu=true && useOpenCL=true → GPU
            if (DeviceFilter.NPU in devices) parts += "runOnCpu = 0"
            if (DeviceFilter.CPU in devices) parts += "(runOnCpu = 1 AND useOpenCL = 0)"
            if (DeviceFilter.GPU in devices) parts += "(runOnCpu = 1 AND useOpenCL = 1)"
            if (parts.isNotEmpty()) {
                where += "(${parts.joinToString(" OR ")})"
            }
        }
        if (!promptSubstring.isNullOrBlank()) {
            where += "(INSTR(prompt, ?) > 0 OR INSTR(negativePrompt, ?) > 0)"
            args += promptSubstring
            args += promptSubstring
        }
        if (!favorites.isNullOrEmpty()) {
            val parts = mutableListOf<String>()
            if (FavoriteFilter.FAVORITE in favorites) parts += "favorite = 1"
            if (FavoriteFilter.NOT_FAVORITE in favorites) parts += "favorite = 0"
            where += "(${parts.joinToString(" OR ")})"
        }

        val whereClause = if (where.isEmpty()) "" else "WHERE ${where.joinToString(" AND ")}"
        val orderClause = if (ordered) {
            val direction = if (descending) "DESC" else "ASC"
            "ORDER BY timestamp $direction, id $direction"
        } else {
            ""
        }
        val limitClause = if (limit != null) "LIMIT $limit" else ""

        val sql = "SELECT $projection FROM generation_history $whereClause $orderClause $limitClause"

        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    fun toJson(): String = JSONObject().apply {
        modelIds?.let { put(KEY_MODEL_IDS, JSONArray(it.toList())) }
        modes?.let { put(KEY_MODES, JSONArray(it.map { m -> m.name })) }
        from?.let { put(KEY_FROM, it) }
        to?.let { put(KEY_TO, it) }
        sizes?.let { put(KEY_SIZES, JSONArray(it.toList())) }
        schedulers?.let { put(KEY_SCHEDULERS, JSONArray(it.toList())) }
        devices?.let { put(KEY_DEVICES, JSONArray(it.map { d -> d.name })) }
        promptSubstring?.let { put(KEY_PROMPT, it) }
        favorites?.let { put(KEY_FAVORITES, JSONArray(it.map { f -> f.name })) }
        put(KEY_DESCENDING, descending)
    }.toString()

    companion object {
        private const val KEY_MODEL_IDS = "modelIds"
        private const val KEY_MODES = "modes"
        private const val KEY_FROM = "from"
        private const val KEY_TO = "to"
        private const val KEY_SIZES = "sizes"
        private const val KEY_SCHEDULERS = "schedulers"
        private const val KEY_DEVICES = "devices"
        private const val KEY_PROMPT = "promptSubstring"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_DESCENDING = "descending"

        fun fromJson(raw: String): HistoryFilter? = runCatching {
            val json = JSONObject(raw)
            HistoryFilter(
                modelIds = json.optJSONArray(KEY_MODEL_IDS)?.toSetOrNull { arr, i -> arr.getString(i) },
                modes = json.optJSONArray(KEY_MODES)?.toSetOrNull { arr, i -> GenerationMode.fromString(arr.getString(i)) },
                from = json.optLong(KEY_FROM, 0L).takeIf { it != 0L },
                to = json.optLong(KEY_TO, 0L).takeIf { it != 0L },
                sizes = json.optJSONArray(KEY_SIZES)?.toSetOrNull { arr, i -> arr.getString(i) },
                schedulers = json.optJSONArray(KEY_SCHEDULERS)?.toSetOrNull { arr, i -> arr.getString(i) },
                devices = json.optJSONArray(KEY_DEVICES)?.toSetOrNull { arr, i -> DeviceFilter.valueOf(arr.getString(i)) },
                promptSubstring = json.optString(KEY_PROMPT).takeIf { it.isNotEmpty() },
                favorites = json.optJSONArray(KEY_FAVORITES)?.toSetOrNull { arr, i -> FavoriteFilter.valueOf(arr.getString(i)) },
                descending = json.optBoolean(KEY_DESCENDING, true),
            )
        }.getOrNull()

        private inline fun <T> JSONArray.toSetOrNull(transform: (JSONArray, Int) -> T): Set<T>? {
            if (length() == 0) return null
            return (0 until length()).map { transform(this, it) }.toSet()
        }
    }
}
