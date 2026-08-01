package io.github.xororz.localdream.modelcatalog

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

enum class RepositoryType { HUGGINGFACE, JSON_INDEX, DIRECTORY }

@Immutable
data class RepositoryConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val type: RepositoryType = RepositoryType.HUGGINGFACE,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("baseUrl", baseUrl)
        put("enabled", enabled)
        put("type", type.name)
    }

    companion object {
        fun fromJson(json: JSONObject): RepositoryConfig = RepositoryConfig(
            id = json.getString("id"),
            name = json.getString("name"),
            baseUrl = json.getString("baseUrl"),
            enabled = json.optBoolean("enabled", true),
            type = runCatching { RepositoryType.valueOf(json.optString("type", "HUGGINGFACE")) }.getOrDefault(RepositoryType.HUGGINGFACE),
        )

        fun serializeList(list: List<RepositoryConfig>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun deserializeList(json: String): List<RepositoryConfig> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }
    }
}
