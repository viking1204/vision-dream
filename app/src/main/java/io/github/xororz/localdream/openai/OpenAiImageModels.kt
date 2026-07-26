package io.github.xororz.localdream.openai

data class ImageRequestParameters(
    val modelId: String?,
    val prompt: String,
    val negativePrompt: String = "",
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int = 28,
    val cfg: Float = 7f,
    val seed: Long? = null,
    val scheduler: String = "dpm",
    val denoiseStrength: Float = 0.6f,
    val sourceImage: ByteArray? = null,
    val maskImage: ByteArray? = null,
    val responseFormat: ImageResponseFormat = ImageResponseFormat.URL,
)

data class UpscaleRequestParameters(
    val modelId: String,
    val sourceImage: ByteArray,
)

enum class ImageResponseFormat {
    B64_JSON,
    URL,
    BINARY,
}

data class GeneratedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val seed: Long?,
)

class OpenAiRequestException(
    val statusCode: Int,
    override val message: String,
    val type: String = "invalid_request_error",
    val parameter: String? = null,
    val code: String? = null,
) : RuntimeException(message)
