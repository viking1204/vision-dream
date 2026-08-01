package io.github.xororz.localdream.data

import android.content.Context

enum class AssetLayoutMode {
    WATERFALL,
    LIST,
    GRID,
    ;

    companion object {
        fun fromPersisted(value: String?): AssetLayoutMode = entries.firstOrNull {
            it.name == value
        } ?: WATERFALL
    }
}

class AssetBrowserPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun layoutMode(): AssetLayoutMode = AssetLayoutMode.fromPersisted(
        preferences.getString(KEY_LAYOUT_MODE, null),
    )

    fun setLayoutMode(value: AssetLayoutMode) {
        preferences.edit().putString(KEY_LAYOUT_MODE, value.name).apply()
    }

    fun getHistoryFilterJson(): String? = preferences.getString(KEY_HISTORY_FILTER, null)

    fun setHistoryFilterJson(json: String?) {
        if (json == null) {
            preferences.edit().remove(KEY_HISTORY_FILTER).apply()
        } else {
            preferences.edit().putString(KEY_HISTORY_FILTER, json).apply()
        }
    }

    fun getAssetScrollIndex(): Int = preferences.getInt(KEY_SCROLL_INDEX, 0)

    fun getAssetScrollOffset(): Int = preferences.getInt(KEY_SCROLL_OFFSET, 0)

    fun setAssetScroll(index: Int, offset: Int) {
        preferences.edit()
            .putInt(KEY_SCROLL_INDEX, index)
            .putInt(KEY_SCROLL_OFFSET, offset)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "asset_browser"
        const val KEY_LAYOUT_MODE = "layout_mode"
        const val KEY_HISTORY_FILTER = "history_filter"
        const val KEY_SCROLL_INDEX = "asset_scroll_index"
        const val KEY_SCROLL_OFFSET = "asset_scroll_offset"
    }
}
