package io.github.xororz.localdream.openai

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import io.github.xororz.localdream.data.AssetOrigin
import io.github.xororz.localdream.data.GenerationDefaults
import io.github.xororz.localdream.data.GenerationMode
import io.github.xororz.localdream.data.HistoryManager
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.service.BackendService
import io.github.xororz.localdream.ui.screens.GenerationParameters
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.json.JSONObject

class OpenAiApiController(
    private val context: Context,
    private val apiKey: String,
    private val executor: BoundedSerialExecutor,
    private val onQueueChanged: (active: Boolean, queued: Int) -> Unit,
) {
    private val catalog = InstalledModelCatalog(context)
    private val coordinator = BackendRuntimeCoordinator(context)
    private val backendClient = NativeBackendClient()
    private val multipartParser = OpenAiMultipartParser()
    private val historyManager = HistoryManager(context)
    private val temporaryImages = TemporaryImageStore()

    fun route(request: HttpRequest): HttpResponse {
        if (request.method == "GET" &&
            TemporaryImageStore.tokenFromPath(request.path) != null
        ) {
            return downloadImage(request.path)
        }
        if (!isAuthorized(request.header("Authorization"))) {
            return error(
                status = 401,
                message = "Invalid or missing bearer token",
                type = "authentication_error",
                code = "invalid_api_key",
                extraHeaders = mapOf("WWW-Authenticate" to "Bearer"),
            )
        }
        return try {
            when {
                request.method == "GET" &&
                    (request.path == "/v1/models" || request.path == "/models") -> models()

                request.method == "GET" && request.path == "/health" -> health()

                request.method == "POST" &&
                    request.path == "/v1/images/generations" -> generation(request)

                request.method == "POST" &&
                    request.path == "/v1/images/edits" -> edit(request)

                request.method == "POST" &&
                    request.path == "/v1/images/upscales" -> upscale(request)

                else -> error(404, "Route not found", code = "not_found")
            }
        } catch (e: OpenAiRequestException) {
            error(
                status = e.statusCode,
                message = e.message,
                type = e.type,
                parameter = e.parameter,
                code = e.code,
            )
        } catch (e: MultipartParseException) {
            error(
                status = 400,
                message = e.message ?: "Invalid multipart request",
                parameter = "image",
                code = e.reason.name.lowercase(),
            )
        } catch (_: JSONException) {
            error(400, "Request body is not valid JSON", code = "invalid_json")
        }
    }

    fun cancelActiveCalls() {
        backendClient.cancelAll()
        temporaryImages.clear()
    }

    private fun models(): HttpResponse {
        val entries = runBlocking { catalog.all() }
        val models = entries.map { entry ->
            val path = File(Model.getModelsDir(context), entry.id)
            OpenAiModel(
                id = entry.id,
                created = (path.lastModified() / 1000L).coerceAtLeast(0L),
            )
        }
        return HttpResponse.json(200, OpenAiJson.models(models))
    }

    private fun health(): HttpResponse = HttpResponse.json(
        200,
        """{"status":"ok","active":${executor.hasActiveTask},"queued":${executor.queuedTaskCount}}""",
    )

    private fun generation(request: HttpRequest): HttpResponse {
        requireJsonContentType(request)
        val body = JSONObject(request.bodyAsUtf8())
        val responseFormat = parseResponseFormat(body.stringValue("response_format"))
        validateCommonOutputOptions(
            n = body.intValue("n", 1),
            outputFormat = body.stringValue("output_format"),
            stream = body.booleanValue("stream", false),
            background = body.stringValue("background"),
        )
        val (width, height) = parseSize(body.stringValue("size"))
        val parameters = ImageRequestParameters(
            modelId = body.stringValue("model"),
            prompt = body.requiredString("prompt"),
            negativePrompt = GenerationDefaults.resolveNegativePrompt(
                body.stringValue("negative_prompt"),
            ),
            width = width,
            height = height,
            steps = body.intValue("steps", DEFAULT_STEPS),
            cfg = body.floatValue("cfg", DEFAULT_CFG),
            seed = body.longValue("seed"),
            scheduler = body.stringValue("scheduler") ?: DEFAULT_SCHEDULER,
            denoiseStrength = body.floatValue("denoise_strength", DEFAULT_DENOISE),
            responseFormat = responseFormat,
        )
        validateParameters(parameters, requiresImage = false)
        val entry = resolveGenerationEntry(parameters.modelId)
        val responseHost = request.header("Host")
        Log.i(
            TAG,
            "Generation request model=${entry.id} responseFormat=${parameters.responseFormat}",
        )
        return submit(entry.id) { executeGeneration(parameters, entry, responseHost) }
    }

    private fun edit(request: HttpRequest): HttpResponse {
        val form = multipartParser.parse(request.header("Content-Type"), request.body)
        validateUploadedImage(form.image.bytes, "image", EDIT_IMAGE_LIMITS)
        form.mask?.let { validateUploadedImage(it.bytes, "mask", EDIT_IMAGE_LIMITS) }
        val responseFormat = parseResponseFormat(form.fields["response_format"])
        validateCommonOutputOptions(
            n = form.fields.intValue("n", 1),
            outputFormat = form.fields["output_format"],
            stream = form.fields.booleanValue("stream", false),
            background = form.fields["background"],
        )
        val (width, height) = parseSize(form.fields["size"])
        val parameters = ImageRequestParameters(
            modelId = form.fields["model"],
            prompt = form.fields.required("prompt"),
            negativePrompt = GenerationDefaults.resolveNegativePrompt(
                form.fields["negative_prompt"],
            ),
            width = width,
            height = height,
            steps = form.fields.intValue("steps", DEFAULT_STEPS),
            cfg = form.fields.floatValue("cfg", DEFAULT_CFG),
            seed = form.fields.longValue("seed"),
            scheduler = form.fields["scheduler"] ?: DEFAULT_SCHEDULER,
            denoiseStrength = form.fields.floatValue("denoise_strength", DEFAULT_DENOISE),
            sourceImage = form.image.bytes,
            maskImage = form.mask?.bytes,
            responseFormat = responseFormat,
        )
        validateParameters(parameters, requiresImage = true)
        val entry = resolveGenerationEntry(parameters.modelId)
        if (!entry.supportsImageInput) {
            throw OpenAiRequestException(
                400,
                "Model '${entry.id}' does not include an image encoder",
                parameter = "model",
                code = "image_input_not_supported",
            )
        }
        val responseHost = request.header("Host")
        return submit(entry.id) { executeGeneration(parameters, entry, responseHost) }
    }

    private fun upscale(request: HttpRequest): HttpResponse {
        val form = multipartParser.parse(request.header("Content-Type"), request.body)
        validateUploadedImage(form.image.bytes, "image", UPSCALE_IMAGE_LIMITS)
        val modelId = form.fields.required("model")
        val responseFormat = parseResponseFormat(form.fields["response_format"])
        val entry = runBlocking { catalog.find(modelId) }
            ?: throw OpenAiRequestException(
                404,
                "Model '$modelId' is not installed",
                parameter = "model",
                code = "model_not_found",
            )
        if (entry.kind != InstalledModelCatalog.Kind.UPSCALER) {
            throw OpenAiRequestException(
                400,
                "Model '$modelId' is not an upscaler",
                parameter = "model",
                code = "invalid_model",
            )
        }
        return submit(entry.id) {
            val path = entry.upscalerFile?.absolutePath
                ?: throw OpenAiRequestException(
                    404,
                    "Upscaler '$modelId' is incomplete",
                    parameter = "model",
                    code = "model_not_found",
                )
            runBlocking { coordinator.ensureReady(entry, null, null) }
            val startedAt = System.currentTimeMillis()
            val image = backendClient.upscale(form.image.bytes, path)
            val dimensions = decodeImageDimensions(image.bytes)
            val requestId = UUID.randomUUID().toString()
            persistAsset(
                entry = entry,
                image = image,
                parameters = GenerationParameters(
                    steps = 0,
                    cfg = 0f,
                    seed = null,
                    prompt = "",
                    negativePrompt = "",
                    generationTime = "${System.currentTimeMillis() - startedAt}ms",
                    width = dimensions.first,
                    height = dimensions.second,
                    runOnCpu = false,
                    scheduler = "upscale",
                    mode = GenerationMode.UNKNOWN,
                ),
                mode = GenerationMode.UNKNOWN,
                upscalerId = entry.id,
                requestId = requestId,
            )
            imageResponse(
                image = image,
                responseFormat = responseFormat,
                requestId = requestId,
                responseHost = request.header("Host"),
            )
        }
    }

    private fun resolveGenerationEntry(requestedModelId: String?): InstalledModelCatalog.Entry {
        val entries = runBlocking { catalog.all() }
        val generationModels = entries.filter {
            it.kind == InstalledModelCatalog.Kind.GENERATION
        }
        val requestedId = requestedModelId
            ?: BackendService.servingModelId.value?.takeIf { serving ->
                generationModels.any { it.id == serving }
            }
            ?: generationModels.firstOrNull()?.id
            ?: throw OpenAiRequestException(
                404,
                "No generation model is installed",
                parameter = "model",
                code = "model_not_found",
            )
        return generationModels.firstOrNull { it.id == requestedId }
            ?: throw OpenAiRequestException(
                404,
                "Model '$requestedId' is not installed",
                parameter = "model",
                code = "model_not_found",
            )
    }

    private fun executeGeneration(
        parameters: ImageRequestParameters,
        entry: InstalledModelCatalog.Entry,
        responseHost: String?,
    ): HttpResponse {
        val dimensions = runBlocking {
            coordinator.ensureReady(entry, parameters.width, parameters.height)
        }
        val startedAt = System.currentTimeMillis()
        val image = backendClient.generate(parameters, dimensions.first, dimensions.second)
        val mode = when {
            parameters.maskImage != null -> GenerationMode.INPAINT
            parameters.sourceImage != null -> GenerationMode.IMG2IMG
            else -> GenerationMode.TXT2IMG
        }
        val requestId = UUID.randomUUID().toString()
        persistAsset(
            entry = entry,
            image = image,
            parameters = GenerationParameters(
                steps = parameters.steps,
                cfg = parameters.cfg,
                seed = image.seed ?: parameters.seed,
                prompt = parameters.prompt,
                negativePrompt = parameters.negativePrompt,
                generationTime = "${System.currentTimeMillis() - startedAt}ms",
                width = dimensions.first,
                height = dimensions.second,
                runOnCpu = entry.model?.runOnCpu == true,
                denoiseStrength = parameters.denoiseStrength,
                useOpenCL = false,
                scheduler = parameters.scheduler,
                mode = mode,
            ),
            mode = mode,
            requestId = requestId,
        )
        return imageResponse(
            image = image,
            responseFormat = parameters.responseFormat,
            requestId = requestId,
            responseHost = responseHost,
        )
    }

    private fun persistAsset(
        entry: InstalledModelCatalog.Entry,
        image: GeneratedImage,
        parameters: GenerationParameters,
        mode: GenerationMode,
        requestId: String,
        upscalerId: String? = null,
    ) {
        val saved = runBlocking {
            historyManager.saveEncodedImage(
                modelId = entry.id,
                encodedImage = image.bytes,
                mimeType = image.mimeType,
                params = parameters,
                mode = mode,
                upscalerId = upscalerId,
                origin = AssetOrigin.OPENAI_API,
                requestId = requestId,
            )
        }
        if (saved == null) {
            throw OpenAiRequestException(
                statusCode = 500,
                message = "Generated image could not be saved to the asset manager",
                type = "server_error",
                code = "asset_persistence_failed",
            )
        }
    }

    private fun submit(
        affinityKey: String,
        operation: () -> HttpResponse,
    ): HttpResponse {
        val submission = InferenceArbiter.process.submitForApi(
            executor = executor,
            affinityKey = affinityKey,
        ) {
            onQueueChanged(executor.hasActiveTask, executor.queuedTaskCount)
            try {
                operation()
            } finally {
                // The executor promotes the next item immediately after this
                // lambda returns. Publish that imminent state rather than the
                // still-active task visible from inside its own finally block.
                val waiting = executor.queuedTaskCount
                onQueueChanged(waiting > 0, (waiting - 1).coerceAtLeast(0))
            }
        }
        if (submission == null) {
            return error(
                status = 409,
                message = "An in-app image generation is already running",
                code = "in_app_generation_active",
                extraHeaders = mapOf("Retry-After" to "5"),
            )
        }
        onQueueChanged(executor.hasActiveTask, executor.queuedTaskCount)
        return when (submission) {
            is BoundedSerialExecutor.Submission.Accepted -> try {
                submission.future.get()
            } catch (_: CancellationException) {
                error(
                    503,
                    "Request cancelled because the API service is stopping",
                    type = "server_error",
                    code = "service_stopping",
                )
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                error(
                    503,
                    "Request interrupted because the API service is stopping",
                    type = "server_error",
                    code = "service_stopping",
                )
            } catch (e: ExecutionException) {
                throw mapExecutionFailure(e.cause ?: e)
            }

            is BoundedSerialExecutor.Submission.Rejected -> when (submission.reason) {
                BoundedSerialExecutor.RejectionReason.QUEUE_FULL -> error(
                    429,
                    "The image request queue is full",
                    code = "queue_full",
                    extraHeaders = mapOf("Retry-After" to "5"),
                )

                BoundedSerialExecutor.RejectionReason.SHUTDOWN -> error(
                    503,
                    "The API service is stopping",
                    type = "server_error",
                    code = "service_stopping",
                )
            }
        }
    }

    private fun imageResponse(
        image: GeneratedImage,
        responseFormat: ImageResponseFormat,
        requestId: String,
        responseHost: String?,
    ): HttpResponse = when (responseFormat) {
        ImageResponseFormat.B64_JSON -> HttpResponse.json(
            200,
            OpenAiJson.images(
                created = System.currentTimeMillis() / 1000L,
                images = listOf(OpenAiImage(Base64.getEncoder().encodeToString(image.bytes))),
            ),
            headers = mapOf("X-Request-Id" to requestId),
        )

        ImageResponseFormat.URL -> {
            val token = try {
                temporaryImages.register(image, requestId)
            } catch (error: IllegalArgumentException) {
                throw OpenAiRequestException(
                    statusCode = 500,
                    message = error.message ?: "Generated image is too large for a temporary URL",
                    type = "server_error",
                    code = "temporary_image_unavailable",
                )
            }
            val url = TemporaryImageStore.downloadUrl(
                hostHeader = responseHost,
                token = token,
                fallbackPort = OpenAiApiPreferences.PORT,
            )
            HttpResponse.json(
                200,
                OpenAiJson.images(
                    created = System.currentTimeMillis() / 1000L,
                    images = listOf(OpenAiImage(url = url)),
                ),
                headers = mapOf("X-Request-Id" to requestId),
            )
        }

        ImageResponseFormat.BINARY -> HttpResponse.binary(
            statusCode = 200,
            body = image.bytes,
            contentType = image.mimeType,
            headers = mapOf("X-Request-Id" to requestId),
        )
    }

    private fun downloadImage(path: String): HttpResponse {
        val token = TemporaryImageStore.tokenFromPath(path)
            ?: return error(404, "Image not found", code = "image_not_found")
        val image = temporaryImages.get(token)
            ?: return error(404, "Image not found or expired", code = "image_not_found")
        return HttpResponse.binary(
            statusCode = 200,
            body = image.bytes,
            contentType = image.mimeType,
            headers = mapOf(
                "X-Request-Id" to image.requestId,
                "X-Image-Expires-At" to image.expiresAtMillis.toString(),
            ),
        )
    }

    private fun decodeImageDimensions(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw OpenAiRequestException(
                statusCode = 500,
                message = "Generated image could not be decoded",
                type = "server_error",
                code = "invalid_generated_image",
            )
        }
        return options.outWidth to options.outHeight
    }

    private fun mapExecutionFailure(error: Throwable): RuntimeException = when (error) {
        is OpenAiRequestException -> error

        else -> OpenAiRequestException(
            statusCode = 500,
            message = error.message ?: "Image inference failed",
            type = "server_error",
            code = "inference_failed",
        )
    }

    fun isAuthorized(header: String?): Boolean {
        val supplied = header
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
            ?.trim()
            ?: return false
        return MessageDigest.isEqual(
            supplied.toByteArray(StandardCharsets.UTF_8),
            apiKey.toByteArray(StandardCharsets.UTF_8),
        )
    }

    fun isTransportAuthorized(
        method: String,
        path: String,
        authorization: String?,
    ): Boolean {
        val temporaryImageRequest = method == "GET" &&
            TemporaryImageStore.tokenFromPath(path) != null
        return temporaryImageRequest || isAuthorized(authorization)
    }

    private fun error(
        status: Int,
        message: String,
        type: String = "invalid_request_error",
        parameter: String? = null,
        code: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse = HttpResponse.json(
        status,
        OpenAiJson.error(OpenAiError(message, type, parameter, code)),
        extraHeaders,
    )

    companion object {
        private const val TAG = "OpenAiApiController"
        private const val DEFAULT_STEPS = 28
        private const val DEFAULT_CFG = 7f
        private const val DEFAULT_DENOISE = 0.6f
        private const val DEFAULT_SCHEDULER = "dpm"
    }
}
