package io.github.xororz.localdream.openai

data class OpenAiError(
    val message: String,
    val type: String = "invalid_request_error",
    val param: String? = null,
    val code: String? = null,
) {
    init {
        require(message.isNotBlank()) { "message must not be blank" }
        require(type.isNotBlank()) { "type must not be blank" }
    }
}

data class OpenAiModel(
    val id: String,
    val created: Long,
    val ownedBy: String = "vision-dream",
    /**
     * Human-readable label. Optional so the envelope stays a strict superset of
     * the OpenAI schema; OpenAI-only clients ignore it.
     */
    val name: String? = null,
    /** "generation" or "upscaler" — distinguishes the two model families exposed by this gateway. */
    val type: String? = null,
    /** Backend identifier, e.g. "sdxl", "sd15npu", "anima", "upscaler". */
    val backendType: String? = null,
    /**
     * Per-model capability advertisement in an OpenAI-compatible extension
     * shape. Lets clients distinguish text-to-image, image-to-image (edit)
     * and upscale models without guessing from the id.
     */
    val capabilities: ModelCapabilities? = null,
) {
    data class ModelCapabilities(
        val imageGeneration: Boolean,
        val imageEdit: Boolean,
        val imageUpscale: Boolean,
    )

    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(created >= 0L) { "created must not be negative" }
        require(ownedBy.isNotBlank()) { "ownedBy must not be blank" }
    }
}

data class OpenAiImage(
    val b64Json: String? = null,
    val url: String? = null,
    val revisedPrompt: String? = null,
) {
    init {
        require((b64Json == null) xor (url == null)) {
            "exactly one image payload must be present"
        }
        require(b64Json == null || b64Json.isNotBlank()) { "b64Json must not be blank" }
        require(url == null || url.isNotBlank()) { "url must not be blank" }
    }
}

/**
 * Dependency-free serializers for the OpenAI response envelopes emitted by the gateway.
 */
object OpenAiJson {
    fun error(error: OpenAiError): String = buildString {
        append("""{"error":{""")
        appendNameAndString("message", error.message)
        append(',')
        appendNameAndString("type", error.type)
        append(',')
        appendNameAndNullableString("param", error.param)
        append(',')
        appendNameAndNullableString("code", error.code)
        append("}}")
    }

    fun models(models: List<OpenAiModel>): String = buildString {
        append("""{"object":"list","data":[""")
        models.forEachIndexed { index, model ->
            if (index > 0) append(',')
            append(modelObject(model))
        }
        append("]}")
    }

    fun model(model: OpenAiModel): String = buildString {
        append(modelObject(model))
    }

    private fun modelObject(model: OpenAiModel): String = buildString {
        append('{')
        appendNameAndString("id", model.id)
        append(""","object":"model","created":""")
        append(model.created)
        append(',')
        appendNameAndString("owned_by", model.ownedBy)
        model.name?.let {
            append(',')
            appendNameAndString("name", it)
        }
        model.type?.let {
            append(',')
            appendNameAndString("type", it)
        }
        model.backendType?.let {
            append(',')
            appendNameAndString("backend_type", it)
        }
        model.capabilities?.let { caps ->
            append(',')
            append("\"capabilities\":{")
            append("\"image_generation\":").append(if (caps.imageGeneration) "true" else "false")
            append(",\"image_edit\":").append(if (caps.imageEdit) "true" else "false")
            append(",\"image_upscale\":").append(if (caps.imageUpscale) "true" else "false")
            append('}')
        }
        append('}')
    }

    fun images(
        created: Long,
        images: List<OpenAiImage>,
        diagnostics: NativeGenerationDiagnostics? = null,
    ): String {
        require(created >= 0L) { "created must not be negative" }
        return buildString {
            append("""{"created":""")
            append(created)
            append(""","data":[""")
            images.forEachIndexed { index, image ->
                if (index > 0) append(',')
                append('{')
                image.b64Json?.let {
                    appendNameAndString("b64_json", it)
                } ?: appendNameAndString("url", requireNotNull(image.url))
                image.revisedPrompt?.let { prompt ->
                    append(',')
                    appendNameAndString("revised_prompt", prompt)
                }
                append('}')
            }
            append("]")
            diagnostics?.unetMs?.takeIf { it > 0L }?.let { unetMs ->
                append(",\"vendor_diagnostics\":{\"unet_ms\":")
                append(unetMs)
                append('}')
            }
            append('}')
        }
    }

    private fun StringBuilder.appendNameAndString(
        name: String,
        value: String,
    ) {
        appendJsonString(name)
        append(':')
        appendJsonString(value)
    }

    private fun StringBuilder.appendNameAndNullableString(
        name: String,
        value: String?,
    ) {
        appendJsonString(name)
        append(':')
        if (value == null) {
            append("null")
        } else {
            appendJsonString(value)
        }
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")

                '\\' -> append("\\\\")

                '\b' -> append("\\b")

                '\u000C' -> append("\\f")

                '\n' -> append("\\n")

                '\r' -> append("\\r")

                '\t' -> append("\\t")

                else -> {
                    if (
                        character.code < ASCII_SPACE ||
                        character == '\u2028' ||
                        character == '\u2029' ||
                        Character.isSurrogate(character)
                    ) {
                        appendUnicodeEscape(character)
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }

    private fun StringBuilder.appendUnicodeEscape(character: Char) {
        append("\\u")
        append(character.code.toString(16).padStart(4, '0'))
    }

    private const val ASCII_SPACE = 0x20
}
