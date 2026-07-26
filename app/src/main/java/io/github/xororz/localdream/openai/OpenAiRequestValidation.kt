package io.github.xororz.localdream.openai

import android.graphics.BitmapFactory
import org.json.JSONObject

/**
 * Request parsing and validation shared by the gateway routes.
 *
 * Keeping these transport rules outside the controller makes admission logic
 * auditable without mixing it with model switching, inference, and assets.
 */
internal fun validateParameters(
    parameters: ImageRequestParameters,
    requiresImage: Boolean,
) {
    if (parameters.prompt.isBlank() || parameters.prompt.length > MAX_PROMPT_CHARACTERS) {
        throw OpenAiRequestException(
            400,
            "prompt must contain 1–$MAX_PROMPT_CHARACTERS characters",
            parameter = "prompt",
            code = "invalid_prompt",
        )
    }
    if (parameters.steps !in 1..MAX_STEPS) {
        throw OpenAiRequestException(
            400,
            "steps must be between 1 and $MAX_STEPS",
            parameter = "steps",
            code = "invalid_steps",
        )
    }
    if (parameters.cfg !in 0f..MAX_CFG || !parameters.cfg.isFinite()) {
        throw OpenAiRequestException(
            400,
            "cfg must be between 0 and $MAX_CFG",
            parameter = "cfg",
            code = "invalid_cfg",
        )
    }
    if (parameters.denoiseStrength !in 0f..1f || !parameters.denoiseStrength.isFinite()) {
        throw OpenAiRequestException(
            400,
            "denoise_strength must be between 0 and 1",
            parameter = "denoise_strength",
            code = "invalid_denoise_strength",
        )
    }
    if (parameters.scheduler !in SUPPORTED_SCHEDULERS) {
        throw OpenAiRequestException(
            400,
            "Unsupported scheduler '${parameters.scheduler}'",
            parameter = "scheduler",
            code = "unsupported_scheduler",
        )
    }
    if (requiresImage && parameters.sourceImage == null) {
        throw OpenAiRequestException(
            400,
            "image is required",
            parameter = "image",
            code = "missing_image",
        )
    }
}

internal fun validateUploadedImage(
    bytes: ByteArray,
    parameter: String,
    limits: ImageUploadLimits,
) {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) {
        throw OpenAiRequestException(
            400,
            "$parameter could not be decoded",
            parameter = parameter,
            code = "invalid_image",
        )
    }
    if (!limits.accepts(options.outWidth, options.outHeight)) {
        throw OpenAiRequestException(
            400,
            "$parameter dimensions are too large",
            parameter = parameter,
            code = "image_too_large",
        )
    }
}

internal fun validateCommonOutputOptions(
    n: Int,
    outputFormat: String?,
    stream: Boolean,
    background: String?,
) {
    if (n != 1) {
        throw OpenAiRequestException(
            400,
            "Only n=1 is supported",
            parameter = "n",
            code = "unsupported_parameter",
        )
    }
    if (outputFormat != null && outputFormat != "png") {
        throw OpenAiRequestException(
            400,
            "Only output_format=png is supported",
            parameter = "output_format",
            code = "unsupported_parameter",
        )
    }
    if (stream) {
        throw OpenAiRequestException(
            400,
            "Streaming image responses are not supported",
            parameter = "stream",
            code = "unsupported_parameter",
        )
    }
    if (background == "transparent") {
        throw OpenAiRequestException(
            400,
            "Transparent backgrounds are not supported",
            parameter = "background",
            code = "unsupported_parameter",
        )
    }
}

internal fun parseResponseFormat(value: String?): ImageResponseFormat = when (value) {
    "b64_json" -> ImageResponseFormat.B64_JSON

    null, "url" -> ImageResponseFormat.URL

    "binary" -> ImageResponseFormat.BINARY

    else -> throw OpenAiRequestException(
        400,
        "response_format must be b64_json, url, or binary",
        parameter = "response_format",
        code = "unsupported_parameter",
    )
}

internal fun parseSize(value: String?): Pair<Int?, Int?> {
    if (value.isNullOrBlank() || value == "auto") return null to null
    val match = SIZE_PATTERN.matchEntire(value)
        ?: throw OpenAiRequestException(
            400,
            "size must be WIDTHxHEIGHT, WIDTH*HEIGHT, or auto",
            parameter = "size",
            code = "invalid_size",
        )
    return match.groupValues[1].toInt() to match.groupValues[2].toInt()
}

internal fun requireJsonContentType(request: HttpRequest) {
    val mediaType = request.header("Content-Type")?.substringBefore(';')?.trim()
    if (!mediaType.equals("application/json", ignoreCase = true)) {
        throw OpenAiRequestException(
            400,
            "Content-Type must be application/json",
            code = "invalid_content_type",
        )
    }
}

internal fun JSONObject.requiredString(name: String): String = stringValue(name)?.takeIf { it.isNotBlank() }
    ?: throw OpenAiRequestException(
        400,
        "$name is required",
        parameter = name,
        code = "missing_parameter",
    )

internal fun JSONObject.stringValue(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return opt(name) as? String
        ?: throw OpenAiRequestException(
            400,
            "$name must be a string",
            parameter = name,
            code = "invalid_parameter",
        )
}

internal fun JSONObject.intValue(name: String, default: Int): Int {
    if (!has(name) || isNull(name)) return default
    val number = opt(name) as? Number
        ?: throw OpenAiRequestException(
            400,
            "$name must be an integer",
            parameter = name,
            code = "invalid_parameter",
        )
    val long = number.toLong()
    if (number.toDouble() != long.toDouble() || long !in Int.MIN_VALUE..Int.MAX_VALUE) {
        throw OpenAiRequestException(
            400,
            "$name must be an integer",
            parameter = name,
            code = "invalid_parameter",
        )
    }
    return long.toInt()
}

internal fun JSONObject.longValue(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    val number = opt(name) as? Number
        ?: throw OpenAiRequestException(
            400,
            "$name must be an integer",
            parameter = name,
            code = "invalid_parameter",
        )
    val value = number.toLong()
    if (number.toDouble() != value.toDouble()) {
        throw OpenAiRequestException(
            400,
            "$name must be an integer",
            parameter = name,
            code = "invalid_parameter",
        )
    }
    return value
}

internal fun JSONObject.floatValue(name: String, default: Float): Float {
    if (!has(name) || isNull(name)) return default
    return (opt(name) as? Number)?.toFloat()
        ?: throw OpenAiRequestException(
            400,
            "$name must be a number",
            parameter = name,
            code = "invalid_parameter",
        )
}

internal fun JSONObject.booleanValue(name: String, default: Boolean): Boolean {
    if (!has(name) || isNull(name)) return default
    return opt(name) as? Boolean
        ?: throw OpenAiRequestException(
            400,
            "$name must be a boolean",
            parameter = name,
            code = "invalid_parameter",
        )
}

internal fun Map<String, String>.required(name: String): String = get(name)?.takeIf { it.isNotBlank() }
    ?: throw OpenAiRequestException(
        400,
        "$name is required",
        parameter = name,
        code = "missing_parameter",
    )

internal fun Map<String, String>.intValue(name: String, default: Int): Int = get(name)?.toIntOrNull() ?: if (containsKey(name)) {
    throw OpenAiRequestException(
        400,
        "$name must be an integer",
        parameter = name,
        code = "invalid_parameter",
    )
} else {
    default
}

internal fun Map<String, String>.longValue(name: String): Long? = get(name)?.toLongOrNull() ?: if (containsKey(name)) {
    throw OpenAiRequestException(
        400,
        "$name must be an integer",
        parameter = name,
        code = "invalid_parameter",
    )
} else {
    null
}

internal fun Map<String, String>.floatValue(name: String, default: Float): Float = get(name)?.toFloatOrNull() ?: if (containsKey(name)) {
    throw OpenAiRequestException(
        400,
        "$name must be a number",
        parameter = name,
        code = "invalid_parameter",
    )
} else {
    default
}

internal fun Map<String, String>.booleanValue(name: String, default: Boolean): Boolean = when (val value = get(name)) {
    null -> default

    "true" -> true

    "false" -> false

    else -> throw OpenAiRequestException(
        400,
        "$name must be true or false",
        parameter = name,
        code = "invalid_parameter",
    )
}

private const val MAX_STEPS = 100
private const val MAX_CFG = 30f
private const val MAX_PROMPT_CHARACTERS = 32_000

// Tavo's image script presents dimensions as "1024*1024" while the standard
// OpenAI spelling is "1024x1024". Treat ASCII and typographic multiplication
// signs as equivalent transport separators before runtime-specific validation.
private val SIZE_PATTERN = Regex("""^(\d{2,4})[xX*×](\d{2,4})$""")
private val SUPPORTED_SCHEDULERS = setOf(
    "dpm",
    "dpm_karras",
    "dpm_sde",
    "dpm_sde_karras",
    "euler",
    "euler_karras",
    "euler_a",
    "euler_a_karras",
    "lcm",
)
