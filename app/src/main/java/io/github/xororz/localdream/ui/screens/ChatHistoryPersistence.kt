package io.github.xororz.localdream.ui.screens

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Conversation messages shown in the creation (chat) screen. Hoisted to a
 * shared file so the persistence round-trip can be unit-tested without the
 * Compose screen.
 */
internal sealed interface ChatGenerationMessage {
    val id: Long

    data class User(
        override val id: Long,
        val prompt: String,
    ) : ChatGenerationMessage

    data class Image(
        override val id: Long,
        val file: File?,
        val fallbackBytes: ByteArray?,
        val modelName: String,
        val width: Int,
        val height: Int,
        val seed: Long?,
        val prompt: String = "",
        val negativePrompt: String = "",
        val steps: Int = 20,
        val cfg: Float = 7f,
        val scheduler: String = "dpm",
        /**
         * Wall-clock generation duration already formatted for display (e.g.
         * "12.3s"). Empty when unknown, which is the case for conversations
         * restored from a build that predates this field.
         */
        val generationTime: String = "",
    ) : ChatGenerationMessage

    data class Error(
        override val id: Long,
        val message: String,
    ) : ChatGenerationMessage
}

/**
 * Serializes the creation conversation to a compact JSON envelope. Only images
 * backed by a durable asset file are persisted; an image whose save to the
 * asset manager failed has no recoverable file and is skipped.
 */
internal fun List<ChatGenerationMessage>.toChatHistoryJson(): String {
    val arr = JSONArray()
    for (message in this) {
        val obj = when (message) {
            is ChatGenerationMessage.User -> JSONObject().apply {
                put("t", "u")
                put("p", message.prompt)
            }

            is ChatGenerationMessage.Image -> {
                val path = message.file?.absolutePath
                if (path != null) {
                    JSONObject().apply {
                        put("t", "i")
                        put("f", path)
                        put("mn", message.modelName)
                        put("w", message.width)
                        put("h", message.height)
                        if (message.seed != null) put("s", message.seed) else put("s", JSONObject.NULL)
                        put("pr", message.prompt)
                        put("np", message.negativePrompt)
                        put("st", message.steps)
                        put("cf", message.cfg)
                        put("sc", message.scheduler)
                        put("gt", message.generationTime)
                    }
                } else {
                    null
                }
            }

            is ChatGenerationMessage.Error -> JSONObject().apply {
                put("t", "e")
                put("m", message.message)
            }
        }
        obj?.let { arr.put(it) }
    }
    return arr.toString()
}

/**
 * Rebuilds the creation conversation from the JSON envelope. Images whose
 * referenced asset file no longer exists are dropped (the asset was deleted
 * from the gallery), keeping the restored list consistent with disk state.
 */
internal fun chatHistoryFromJson(raw: String): List<ChatGenerationMessage>? = runCatching {
    val arr = JSONArray(raw)
    val out = mutableListOf<ChatGenerationMessage>()
    var nextId = 1L
    for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        when (obj.optString("t")) {
            "u" -> out.add(
                ChatGenerationMessage.User(
                    id = nextId++,
                    prompt = obj.optString("p", ""),
                ),
            )

            "i" -> {
                val file = File(obj.optString("f", "")).takeIf { it.exists() }
                if (file != null) {
                    out.add(
                        ChatGenerationMessage.Image(
                            id = nextId++,
                            file = file,
                            fallbackBytes = null,
                            modelName = obj.optString("mn", ""),
                            width = obj.optInt("w", 512),
                            height = obj.optInt("h", 512),
                            seed = if (!obj.isNull("s")) obj.getLong("s") else null,
                            prompt = obj.optString("pr", ""),
                            negativePrompt = obj.optString("np", ""),
                            steps = obj.optInt("st", 20),
                            cfg = obj.optDouble("cf", 7.0).toFloat(),
                            scheduler = obj.optString("sc", "dpm"),
                            // Absent in envelopes written before generation
                            // timing was tracked; empty simply hides the row.
                            generationTime = obj.optString("gt", ""),
                        ),
                    )
                }
            }

            "e" -> out.add(
                ChatGenerationMessage.Error(
                    id = nextId++,
                    message = obj.optString("m", ""),
                ),
            )
        }
    }
    out
}.getOrNull()
