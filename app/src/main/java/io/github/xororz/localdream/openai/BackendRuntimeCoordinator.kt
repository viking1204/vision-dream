package io.github.xororz.localdream.openai

import android.content.Context
import android.content.Intent
import io.github.xororz.localdream.data.PatchScanner
import io.github.xororz.localdream.service.BackendService
import io.github.xororz.localdream.utils.Http
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Makes a requested backend configuration ready before inference starts.
 *
 * Callers must hold the gateway's serial execution lease. Keeping model
 * switching and inference in that same lease prevents a later request from
 * replacing the process between the health check and the actual call.
 */
class BackendRuntimeCoordinator(private val context: Context) {
    private val healthClient: OkHttpClient = Http.client.newBuilder()
        .connectTimeout(250, TimeUnit.MILLISECONDS)
        .readTimeout(1, TimeUnit.SECONDS)
        .build()

    suspend fun ensureReady(
        entry: InstalledModelCatalog.Entry,
        requestedWidth: Int?,
        requestedHeight: Int?,
    ): Pair<Int, Int> {
        val dimensions = resolveDimensions(entry, requestedWidth, requestedHeight)
        val intent = Intent(context, BackendService::class.java).apply {
            putExtra("modelId", entry.id)
            putExtra("backendType", entry.backendType)
            putExtra("width", dimensions.first)
            putExtra("height", dimensions.second)
            putExtra(
                BackendService.EXTRA_IMAGE_INPUT_ENABLED,
                entry.kind == InstalledModelCatalog.Kind.GENERATION &&
                    entry.supportsImageInput,
            )
            putExtra(
                BackendService.EXTRA_REQUEST_OWNER,
                BackendService.REQUEST_OWNER_OPENAI_API,
            )
        }
        context.startService(intent)
        awaitReady(
            entry.id,
            dimensions,
            entry.kind == InstalledModelCatalog.Kind.GENERATION &&
                entry.supportsImageInput,
        )
        return dimensions
    }

    private fun resolveDimensions(
        entry: InstalledModelCatalog.Entry,
        requestedWidth: Int?,
        requestedHeight: Int?,
    ): Pair<Int, Int> {
        if (entry.kind == InstalledModelCatalog.Kind.UPSCALER) return 512 to 512

        val width = requestedWidth ?: entry.generationSize
        val height = requestedHeight ?: entry.generationSize
        if (width !in MIN_DIMENSION..MAX_DIMENSION ||
            height !in MIN_DIMENSION..MAX_DIMENSION ||
            width % DIMENSION_MULTIPLE != 0 ||
            height % DIMENSION_MULTIPLE != 0 ||
            width.toLong() * height > MAX_GENERATION_PIXELS
        ) {
            throw OpenAiRequestException(
                statusCode = 400,
                message = "Unsupported image size ${width}x$height",
                parameter = "size",
                code = "unsupported_size",
            )
        }

        val model = entry.model
            ?: throw OpenAiRequestException(404, "Model '${entry.id}' is not installed")
        if (model.usesFixedCanvas && (width != model.generationSize || height != model.generationSize)) {
            throw OpenAiRequestException(
                statusCode = 400,
                message = "Model '${entry.id}' requires ${model.generationSize}x${model.generationSize}",
                parameter = "size",
                code = "unsupported_size",
            )
        }
        if (!model.runOnCpu && !model.usesFixedCanvas && (width != 512 || height != 512)) {
            val supported = PatchScanner.scanAvailableResolutions(context, entry.id)
                .any { it.width == width && it.height == height }
            if (!supported) {
                throw OpenAiRequestException(
                    statusCode = 400,
                    message = "Model '${entry.id}' has no ${width}x$height resolution patch",
                    parameter = "size",
                    code = "unsupported_size",
                )
            }
        }
        return width to height
    }

    private suspend fun awaitReady(
        modelId: String,
        dimensions: Pair<Int, Int>,
        imageInputEnabled: Boolean,
    ) {
        val startedAt = System.currentTimeMillis()
        var errorStreak = 0
        while (currentCoroutineContext().isActive) {
            val state = BackendService.backendState.value
            val ownError = state is BackendService.BackendState.Error &&
                (state.modelId == null || state.modelId == modelId)
            if (ownError) {
                errorStreak++
                if (errorStreak >= 2) {
                    throw OpenAiRequestException(
                        statusCode = 503,
                        message = state.message,
                        type = "server_error",
                        code = "backend_start_failed",
                    )
                }
            } else {
                errorStreak = 0
            }

            val matches = state is BackendService.BackendState.Running &&
                BackendService.servingModelId.value == modelId &&
                BackendService.servingResolution.value == dimensions &&
                BackendService.servingImageInputEnabled.value == imageInputEnabled
            if (matches && isHealthy()) return

            if (System.currentTimeMillis() - startedAt >= READY_TIMEOUT_MS) {
                throw OpenAiRequestException(
                    statusCode = 503,
                    message = "Timed out loading model '$modelId'",
                    type = "server_error",
                    code = "backend_start_timeout",
                )
            }
            delay(POLL_INTERVAL_MS)
        }
        throw InterruptedException("Gateway stopped while loading '$modelId'")
    }

    private suspend fun isHealthy(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("http://127.0.0.1:8081/health")
                .get()
                .build()
            healthClient.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val MIN_DIMENSION = 128
        private const val MAX_DIMENSION = 2048
        private const val DIMENSION_MULTIPLE = 64
        private const val MAX_GENERATION_PIXELS = 4_194_304L
        private const val READY_TIMEOUT_MS = 180_000L
        private const val POLL_INTERVAL_MS = 250L
    }
}
