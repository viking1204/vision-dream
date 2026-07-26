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

    private companion object {
        const val PREFERENCES_NAME = "asset_browser"
        const val KEY_LAYOUT_MODE = "layout_mode"
    }
}
