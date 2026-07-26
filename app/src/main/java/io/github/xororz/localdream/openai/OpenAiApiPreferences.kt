package io.github.xororz.localdream.openai

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import java.security.SecureRandom

class OpenAiApiPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun apiKey(): String {
        preferences.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val bytes = ByteArray(API_KEY_BYTES)
        SecureRandom().nextBytes(bytes)
        val generated = Base64.encodeToString(
            bytes,
            Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE,
        )
        preferences.edit(commit = true) { putString(KEY_API_KEY, generated) }
        return generated
    }

    fun regenerateApiKey(): String {
        preferences.edit(commit = true) { remove(KEY_API_KEY) }
        return apiKey()
    }

    fun queueCapacity(): Int = preferences
        .getInt(KEY_QUEUE_CAPACITY, DEFAULT_QUEUE_CAPACITY)
        .coerceIn(MIN_QUEUE_CAPACITY, MAX_QUEUE_CAPACITY)

    fun setQueueCapacity(value: Int) {
        preferences.edit {
            putInt(
                KEY_QUEUE_CAPACITY,
                value.coerceIn(MIN_QUEUE_CAPACITY, MAX_QUEUE_CAPACITY),
            )
        }
    }

    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 3
        const val MIN_QUEUE_CAPACITY = 0
        const val MAX_QUEUE_CAPACITY = 10
        const val PORT = 8809

        private const val PREFERENCES_NAME = "openai_api"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_QUEUE_CAPACITY = "queue_capacity"
        private const val API_KEY_BYTES = 24
    }
}
