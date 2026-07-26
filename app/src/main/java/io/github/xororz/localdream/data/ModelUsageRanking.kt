package io.github.xororz.localdream.data

import android.content.Context
import androidx.core.content.edit

/**
 * Persists model use order independently from the live backend state.
 *
 * Unloading a model intentionally leaves its rank unchanged. Loading another
 * model moves that model to the front and shifts earlier models down, producing
 * a stable most-recently-used ranking across app restarts.
 */
object ModelUsageRanking {
    private const val PREFERENCES_NAME = "app_prefs"
    private const val KEY = "model_usage_ranking"
    private const val SEPARATOR = "\n"

    private fun preferences(context: Context) = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun get(context: Context): List<String> = preferences(context)
        .getString(KEY, "")
        .orEmpty()
        .split(SEPARATOR)
        .filter(String::isNotEmpty)
        .distinct()

    fun record(context: Context, modelId: String) {
        if (modelId.isBlank()) return
        save(context, listOf(modelId) + get(context).filterNot { it == modelId })
    }

    fun remove(context: Context, modelId: String) {
        save(context, get(context).filterNot { it == modelId })
    }

    fun rename(
        context: Context,
        oldId: String,
        newId: String,
    ) {
        val current = get(context)
        if (oldId !in current) return
        save(
            context,
            current.map { if (it == oldId) newId else it }.distinct(),
        )
    }

    /**
     * Used models come first in MRU order. Explicit pins then order models that
     * have never been used, followed by the repository's original ordering.
     */
    fun sort(
        models: List<Model>,
        usageIds: List<String>,
        pinnedIds: List<String>,
    ): List<Model> {
        if (models.size < 2) return models
        val usageRank = usageIds.withIndex().associate { (index, id) -> id to index }
        val pinRank = pinnedIds.withIndex().associate { (index, id) -> id to index }
        return models.withIndex()
            .sortedWith(
                compareBy<IndexedValue<Model>>(
                    { if (it.value.id in usageRank) 0 else 1 },
                    { usageRank[it.value.id] ?: Int.MAX_VALUE },
                    { if (it.value.id in pinRank) 0 else 1 },
                    { pinRank[it.value.id] ?: Int.MAX_VALUE },
                    { it.index },
                ),
            )
            .map(IndexedValue<Model>::value)
    }

    private fun save(
        context: Context,
        ids: List<String>,
    ) {
        preferences(context).edit {
            putString(KEY, ids.distinct().joinToString(SEPARATOR))
        }
    }
}
