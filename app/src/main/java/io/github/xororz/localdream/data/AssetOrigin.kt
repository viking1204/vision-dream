package io.github.xororz.localdream.data

/**
 * Identifies which app entry point created a managed image asset.
 *
 * The persisted values are deliberately stable and independent from enum names
 * so future source renames do not rewrite existing Room rows or backup data.
 */
enum class AssetOrigin(val persistedValue: String) {
    LOCAL_APP("local_app"),
    REMOTE_LINK("remote_link"),
    OPENAI_API("openai_api"),
    CHAT_GENERATION("chat_generation"),
    ;

    companion object {
        fun fromPersistedValue(value: String?): AssetOrigin = entries.firstOrNull {
            it.persistedValue == value
        } ?: LOCAL_APP
    }
}
