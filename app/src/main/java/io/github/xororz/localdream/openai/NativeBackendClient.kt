package io.github.xororz.localdream.openai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.xororz.localdream.utils.Http
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.Base64
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class NativeBackendClient {
    private val client: OkHttpClient = Http.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .callTimeout(60, TimeUnit.MINUTES)
        .retryOnConnectionFailure(false)
        .build()

    fun cancelAll() {
        client.dispatcher.cancelAll()
    }

    fun generate(
        parameters: ImageRequestParameters,
        width: Int,
        height: Int,
        onDiffusionStep: ((step: Int, totalSteps: Int) -> Unit)? = null,
        requestDiffusionPreviews: Boolean = false,
    ): GeneratedImage {
        val progressNormalizer = onDiffusionStep?.let {
            DiffusionProgressNormalizer(parameters.steps)
        }
        // Progress messages do not require previews: the native SSE always
        // emits a step event, while previews trigger an additional VAE decode.
        // In particular, MCP's protocol progress must not change inference work.
        val payload = nativeGenerationPayload(parameters, width, height, requestDiffusionPreviews)
        val request = Request.Builder()
            .url("$BACKEND_URL/generate")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Native generation failed with HTTP ${response.code}")
            }
            val body = response.body
                ?: throw IOException("Native generation returned an empty response")
            val reader = BufferedReader(InputStreamReader(body.byteStream()))
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith(SSE_DATA_PREFIX)) continue
                val data = line.removePrefix(SSE_DATA_PREFIX).trim()
                if (data == "[DONE]") break
                val event = JSONObject(data)
                when (event.optString("type")) {
                    "progress" -> {
                        val step = event.optInt("step")
                        val totalSteps = event.optInt("total_steps")
                        if (step > 0 && totalSteps > 0 && step <= totalSteps) {
                            progressNormalizer?.accept(step, totalSteps)?.let { normalized ->
                                onDiffusionStep.invoke(normalized.first, normalized.second)
                            }
                        }
                    }

                    "complete" -> {
                        val image = event.optString("image")
                        if (image.isBlank()) {
                            throw IOException("Native generation returned no image")
                        }
                        val resultWidth = event.optInt("width", width)
                        val resultHeight = event.optInt("height", height)
                        val normalized = normalizeGeneratedImage(
                            encodedImage = image,
                            width = resultWidth,
                            height = resultHeight,
                        )
                        return GeneratedImage(
                            bytes = normalized.first,
                            mimeType = normalized.second,
                            seed = event.optLong("seed", -1L).takeIf { it >= 0L },
                            diagnostics = nativeDiagnostics(event),
                        )
                    }

                    "error" -> throw IOException(
                        event.optString("message", "Native generation failed"),
                    )
                }
            }
        }
        throw IOException("Native generation ended before a complete event")
    }

    private fun nativeDiagnostics(event: JSONObject): NativeGenerationDiagnostics? {
        val metrics = event.optJSONObject("stage_metrics") ?: return null
        val unetMs = metrics.opt("unet_ms")
            .takeIf { it is Number && it.toLong() > 0L }
            ?.let { (it as Number).toLong() }
        return NativeGenerationDiagnostics(unetMs = unetMs)
    }

    private fun normalizeGeneratedImage(
        encodedImage: String,
        width: Int,
        height: Int,
    ): Pair<ByteArray, String> {
        val bytes = try {
            Base64.getDecoder().decode(encodedImage)
        } catch (e: IllegalArgumentException) {
            throw IOException("Native generation returned invalid base64", e)
        }
        detectEncodedImageMimeType(bytes)?.let { mimeType ->
            return bytes to mimeType
        }
        if (width <= 0 || height <= 0 || bytes.size != width * height * 3) {
            throw IOException("Native generation returned an unsupported image payload")
        }

        val pixels = IntArray(width * height)
        for (index in pixels.indices) {
            val offset = index * 3
            pixels[index] = 0xff000000.toInt() or
                ((bytes[offset].toInt() and 0xff) shl 16) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                (bytes[offset + 2].toInt() and 0xff)
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return try {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw IOException("Failed to encode native RGB result as PNG")
            }
            output.toByteArray() to "image/png"
        } finally {
            bitmap.recycle()
        }
    }

    private fun detectEncodedImageMimeType(bytes: ByteArray): String? {
        val isPng = bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4e.toByte() &&
            bytes[3] == 0x47.toByte()
        val isJpeg = bytes.size >= 3 &&
            bytes[0] == 0xff.toByte() &&
            bytes[1] == 0xd8.toByte() &&
            bytes[2] == 0xff.toByte()
        val isWebp = bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
        return when {
            isPng -> "image/png"
            isJpeg -> "image/jpeg"
            isWebp -> "image/webp"
            else -> null
        }
    }

    fun upscale(sourceImage: ByteArray, upscalerPath: String): GeneratedImage {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(sourceImage, 0, sourceImage.size, options)
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0) {
            throw OpenAiRequestException(
                statusCode = 400,
                message = "The uploaded image could not be decoded",
                parameter = "image",
                code = "invalid_image",
            )
        }
        if (!UPSCALE_IMAGE_LIMITS.accepts(width, height)) {
            throw OpenAiRequestException(
                statusCode = 400,
                message = "Upscale input is too large",
                parameter = "image",
                code = "image_too_large",
            )
        }

        val bitmap = BitmapFactory.decodeByteArray(sourceImage, 0, sourceImage.size)
            ?: throw OpenAiRequestException(
                statusCode = 400,
                message = "The uploaded image could not be decoded",
                parameter = "image",
                code = "invalid_image",
            )
        try {
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val rgb = ByteArray(width * height * 3)
            pixels.forEachIndexed { index, pixel ->
                rgb[index * 3] = ((pixel shr 16) and 0xff).toByte()
                rgb[index * 3 + 1] = ((pixel shr 8) and 0xff).toByte()
                rgb[index * 3 + 2] = (pixel and 0xff).toByte()
            }
            val request = Request.Builder()
                .url("$BACKEND_URL/upscale")
                .header("X-Image-Width", width.toString())
                .header("X-Image-Height", height.toString())
                .header("X-Upscaler-Path", upscalerPath)
                .post(rgb.toRequestBody(BINARY_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Native upscale failed with HTTP ${response.code}")
                }
                val encoded = response.body?.bytes()
                    ?: throw IOException("Native upscale returned an empty response")
                return normalizeUpscaledImageForResponse(encoded)
            }
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        private const val BACKEND_URL = "http://127.0.0.1:8081"
        private const val SSE_DATA_PREFIX = "data: "
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val BINARY_MEDIA_TYPE = "application/octet-stream".toMediaType()
    }
}

/**
 * The native upscaler emits JPEG bytes, while the public OpenAI-compatible
 * contract explicitly accepts only `output_format=png`. Re-encode at this
 * boundary so URL, binary, history and MCP callers all observe the same PNG.
 */
internal fun normalizeUpscaledImageForResponse(encodedImage: ByteArray): GeneratedImage {
    val bitmap = BitmapFactory.decodeByteArray(encodedImage, 0, encodedImage.size)
        ?: throw IOException("Native upscale returned an invalid image")
    return try {
        val output = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
            throw IOException("Failed to encode native upscale result as PNG")
        }
        GeneratedImage(
            bytes = output.toByteArray(),
            mimeType = "image/png",
            seed = null,
        )
    } finally {
        bitmap.recycle()
    }
}

internal fun nativeGenerationPayload(
    parameters: ImageRequestParameters,
    width: Int,
    height: Int,
    requestDiffusionPreviews: Boolean,
): JSONObject = JSONObject().apply {
    put("prompt", parameters.prompt)
    put("negative_prompt", parameters.negativePrompt)
    put("steps", parameters.steps)
    put("cfg", parameters.cfg)
    put("width", width)
    put("height", height)
    put("denoise_strength", parameters.denoiseStrength)
    put("scheduler", parameters.scheduler)
    put("show_diffusion_process", requestDiffusionPreviews)
    put("output_format", "png")
    parameters.seed?.let { put("seed", it) }
    parameters.sourceImage?.let {
        put("image", Base64.getEncoder().encodeToString(it))
    }
    parameters.maskImage?.let {
        put("mask", Base64.getEncoder().encodeToString(it))
    }
}

/**
 * Converts the native pipeline-wide progress stream into the diffusion-only
 * contract exposed by MCP. The native stream currently counts CLIP and VAE
 * stages as well, and the first diffusion step repeats the preceding raw step.
 * Keeping this compatibility logic at the native-client boundary prevents
 * transport code from depending on backend-specific pipeline bookkeeping.
 */
internal class DiffusionProgressNormalizer(
    private val expectedSteps: Int,
) {
    private var previousRawStep: Int? = null
    private var diffusionRawStart: Int? = null
    private var lastEmittedStep = 0

    init {
        require(expectedSteps > 0)
    }

    fun accept(rawStep: Int, rawTotalSteps: Int): Pair<Int, Int>? {
        if (rawStep !in 1..rawTotalSteps) return null
        if (rawTotalSteps == expectedSteps) {
            return emit(rawStep)
        }

        val start = diffusionRawStart
        if (start == null) {
            val previous = previousRawStep
            previousRawStep = rawStep
            if (previous == null || rawStep > previous) return null
            diffusionRawStart = rawStep
        }

        val diffusionStep = rawStep - requireNotNull(diffusionRawStart) + 1
        return emit(diffusionStep)
    }

    private fun emit(diffusionStep: Int): Pair<Int, Int>? {
        if (diffusionStep !in 1..expectedSteps || diffusionStep <= lastEmittedStep) return null
        lastEmittedStep = diffusionStep
        return diffusionStep to expectedSteps
    }
}
