package io.github.xororz.localdream.openai

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import io.github.xororz.localdream.data.AssetOrigin
import io.github.xororz.localdream.data.GenerationDefaults
import io.github.xororz.localdream.data.GenerationMode
import io.github.xororz.localdream.data.HistoryItem
import io.github.xororz.localdream.data.HistoryManager
import io.github.xororz.localdream.data.InferenceHistoryAssociation
import io.github.xororz.localdream.data.InferenceJobStatus
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.data.PerformancePresetConfig
import io.github.xororz.localdream.data.PerformancePresetEngineConfig
import io.github.xororz.localdream.data.RoomInferenceJobRepository
import io.github.xororz.localdream.data.db.AppDatabase
import io.github.xororz.localdream.data.toProtectedProjection
import io.github.xororz.localdream.inference.InferenceDispatcher
import io.github.xororz.localdream.service.BackendService
import io.github.xororz.localdream.service.NativeRuntimeAttestationRecorder
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
    private val dispatcher: InferenceDispatcher,
    private val onQueueChanged: (active: Boolean, queued: Int) -> Unit,
) {
    private val catalog = InstalledModelCatalog(context)
    private val coordinator = BackendRuntimeCoordinator(context)
    private val backendClient = NativeBackendClient()
    private val multipartParser = OpenAiMultipartParser()
    private val historyManager = HistoryManager(context)
    private val temporaryImages = TemporaryImageStore()
    private val inferenceJobs = RoomInferenceJobRepository(AppDatabase.get(context))

    fun route(request: HttpRequest): HttpResponse {
        if (request.method == "GET" &&
            TemporaryImageStore.assetIdFromPath(request.path) != null &&
            TemporaryImageStore.tokenFromQuery(request.query) != null
        ) {
            return downloadImage(request.path, request.query)
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

    private fun health(): HttpResponse {
        val probe = BackendService.runtimeProbe.value.toProtectedProjection()
        return HttpResponse.json(
            200,
            JSONObject()
                .put("status", "ok")
                .put("active", dispatcher.hasActiveTask)
                .put("queued", dispatcher.queuedTaskCount)
                // The harness compares this opaque digest with `adb shell cmd
                // package path`.  It proves that authenticated health came
                // from the same installed app instance that ADB samples,
                // without exposing the private installation path.
                .put(
                    "installation",
                    JSONObject()
                        .put("appPackage", context.packageName)
                        .put("packagePathSha256", sha256(context.applicationInfo.sourceDir)),
                )
                .put(
                    "runtimeProbe",
                    JSONObject()
                        .put("status", probe.status.name)
                        .put("rejectionReasons", org.json.JSONArray(probe.rejectionReasons))
                        .put("deviceModel", probe.deviceModel)
                        .put("soc", probe.soc)
                        .put("abi", probe.abi)
                        .put("qairtVersion", probe.qairtVersion)
                        .put("htpTarget", probe.htpTarget)
                        .put("contextFingerprint", probe.contextFingerprint)
                        .put("loadedLibraryFingerprints", JSONObject(probe.loadedLibraryFingerprints))
                        .put("nativeReady", probe.nativeReady),
                )
                .toString(),
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

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
        val presetId = body.stringValue("preset_id")
        val responseHost = request.header("Host")
        Log.i(
            TAG,
            "Generation request model=${entry.id} responseFormat=${parameters.responseFormat}",
        )
        return submit(entry.id, presetId) { association, presetEngineConfig ->
            executeGeneration(parameters, entry, responseHost, association, presetEngineConfig)
        }
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
        val presetId = form.fields["preset_id"]
        if (!entry.supportsImageInput) {
            throw OpenAiRequestException(
                400,
                "Model '${entry.id}' does not include an image encoder",
                parameter = "model",
                code = "image_input_not_supported",
            )
        }
        val responseHost = request.header("Host")
        return submit(entry.id, presetId) { association, presetEngineConfig ->
            executeGeneration(parameters, entry, responseHost, association, presetEngineConfig)
        }
    }

    private fun upscale(request: HttpRequest): HttpResponse {
        val form = multipartParser.parse(request.header("Content-Type"), request.body)
        validateUploadedImage(form.image.bytes, "image", UPSCALE_IMAGE_LIMITS)
        validateCommonOutputOptions(
            n = form.fields.intValue("n", 1),
            outputFormat = form.fields["output_format"],
            stream = form.fields.booleanValue("stream", false),
            background = form.fields["background"],
        )
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
        return submit(entry.id) { association, _ ->
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
            val asset = persistAsset(
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
                inferenceAssociation = association,
            )
            imageResponse(
                image = image,
                responseFormat = responseFormat,
                requestId = requestId,
                assetId = "history:${asset.id}",
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
        inferenceAssociation: InferenceHistoryAssociation,
        presetEngineConfig: PerformancePresetEngineConfig?,
    ): HttpResponse {
        val dimensions = runBlocking {
            coordinator.ensureReady(entry, parameters.width, parameters.height, presetEngineConfig)
        }
        val startedAt = System.currentTimeMillis()
        val image = backendClient.generate(parameters, dimensions.first, dimensions.second)
        // A valid image is the only point at which a legacy model can acquire
        // runtime evidence. A failed request leaves compatibility fallback on.
        NativeRuntimeAttestationRecorder.record(context, entry.id)
        val mode = when {
            parameters.maskImage != null -> GenerationMode.INPAINT
            parameters.sourceImage != null -> GenerationMode.IMG2IMG
            else -> GenerationMode.TXT2IMG
        }
        val requestId = UUID.randomUUID().toString()
        val asset = persistAsset(
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
            inferenceAssociation = inferenceAssociation,
        )
        return imageResponse(
            image = image,
            responseFormat = parameters.responseFormat,
            requestId = requestId,
            assetId = "history:${asset.id}",
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
        inferenceAssociation: InferenceHistoryAssociation,
    ): HistoryItem {
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
                inferenceAssociation = inferenceAssociation,
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
        return saved
    }

    private fun submit(
        affinityKey: String,
        explicitPresetId: String? = null,
        operation: (InferenceHistoryAssociation, PerformancePresetEngineConfig?) -> HttpResponse,
    ): HttpResponse {
        val acceptedSnapshot = try {
            runBlocking {
                inferenceJobs.accept(
                    ownerId = DISPATCH_OWNER,
                    modelId = affinityKey,
                    explicitPresetId = explicitPresetId,
                )
            }
        } catch (error: IllegalArgumentException) {
            throw OpenAiRequestException(
                statusCode = 400,
                message = "Requested performance preset is not executable",
                parameter = "preset_id",
                code = "preset_incompatible",
            )
        }
        val snapshotEngineConfig = PerformancePresetConfig.parse(acceptedSnapshot.configJson).engine
        val association = InferenceHistoryAssociation(
            jobId = acceptedSnapshot.jobId,
            presetId = acceptedSnapshot.presetId,
            presetRevision = acceptedSnapshot.revision,
        )
        val submission = dispatcher.submit(
            ownerId = DISPATCH_OWNER,
            affinityKey = affinityKey,
        ) {
            onQueueChanged(dispatcher.hasActiveTask, dispatcher.queuedTaskCount)
            try {
                runBlocking { inferenceJobs.updateStatus(association.jobId, InferenceJobStatus.RUNNING) }
                operation(association, snapshotEngineConfig).also {
                    runBlocking {
                        inferenceJobs.updateStatus(association.jobId, InferenceJobStatus.SUCCEEDED)
                    }
                }
            } catch (error: Throwable) {
                runBlocking { inferenceJobs.updateStatus(association.jobId, InferenceJobStatus.FAILED) }
                throw error
            } finally {
                // The executor promotes the next item immediately after this
                // lambda returns. Publish that imminent state rather than the
                // still-active task visible from inside its own finally block.
                val waiting = dispatcher.queuedTaskCount
                onQueueChanged(waiting > 0, (waiting - 1).coerceAtLeast(0))
            }
        }
        if (submission == null) {
            runBlocking { inferenceJobs.discard(association.jobId) }
            return error(
                status = 409,
                message = "An in-app image generation is already running",
                code = "in_app_generation_active",
                extraHeaders = mapOf("Retry-After" to "5"),
            )
        }
        onQueueChanged(dispatcher.hasActiveTask, dispatcher.queuedTaskCount)
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

            is BoundedSerialExecutor.Submission.Rejected -> {
                runBlocking { inferenceJobs.discard(association.jobId) }
                when (submission.reason) {
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
    }

    private fun imageResponse(
        image: GeneratedImage,
        responseFormat: ImageResponseFormat,
        requestId: String,
        assetId: String,
        responseHost: String?,
    ): HttpResponse = when (responseFormat) {
        ImageResponseFormat.B64_JSON -> HttpResponse.json(
            200,
            OpenAiJson.images(
                created = System.currentTimeMillis() / 1000L,
                images = listOf(OpenAiImage(Base64.getEncoder().encodeToString(image.bytes))),
                diagnostics = image.diagnostics,
            ),
            headers = mapOf("X-Request-Id" to requestId),
        )

        ImageResponseFormat.URL -> {
            val token = try {
                temporaryImages.register(image, requestId, assetId)
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
                assetId = assetId,
                token = token,
                fallbackPort = OpenAiApiPreferences.PORT,
            )
            HttpResponse.json(
                200,
                OpenAiJson.images(
                    created = System.currentTimeMillis() / 1000L,
                    images = listOf(OpenAiImage(url = url)),
                    diagnostics = image.diagnostics,
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

    private fun downloadImage(path: String, query: String?): HttpResponse {
        val assetId = TemporaryImageStore.assetIdFromPath(path)
            ?: return error(404, "Image not found", code = "image_not_found")
        val token = TemporaryImageStore.tokenFromQuery(query)
            ?: return error(404, "Image not found", code = "image_not_found")
        val image = temporaryImages.get(assetId, token)
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
        requestTarget: String,
        authorization: String?,
    ): Boolean {
        val temporaryImageRequest = method == "GET" &&
            TemporaryImageStore.capabilityFromTarget(requestTarget) != null
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
        const val DISPATCH_OWNER = "openai-api"
    }
}
