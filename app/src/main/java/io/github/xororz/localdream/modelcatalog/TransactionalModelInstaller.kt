package io.github.xororz.localdream.modelcatalog

import android.content.Context
import android.util.Log
import io.github.xororz.localdream.data.ModelFileLayouts
import io.github.xororz.localdream.data.ModelMetadata
import io.github.xororz.localdream.data.ModelMetadataStore
import io.github.xororz.localdream.data.ModelRepository
import io.github.xororz.localdream.data.ModelStorage
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Installs a downloaded artifact without exposing a partial model directory.
 *
 * Conversion/extraction happens in a same-filesystem staging directory. The
 * validated directory is published with one rename, and an existing target is
 * never overwritten.
 */
class TransactionalModelInstaller(private val context: Context) {
    sealed class Result {
        data class Installed(val modelId: String, val backendType: String) : Result()
        data class AlreadyInstalled(val modelId: String) : Result()
        data class Incompatible(val reason: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    suspend fun installZip(
        downloadedFile: File,
        requestedModelId: String,
        expectation: CatalogInstallExpectation,
        metadata: ModelMetadata = ModelMetadata(),
        onProgress: (String) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        install(requestedModelId) { staging ->
            if (!CatalogDeviceCompatibility.isCurrentDeviceCompatible(expectation)) {
                return@install Result.Incompatible(
                    "The archive is not compatible with this device",
                )
            }
            onProgress("Extracting model")
            extractFlatZip(downloadedFile, staging)
            val backendType = detectBackendType(staging)
                ?: return@install Result.Incompatible(
                    "The archive does not contain a complete supported model",
                )
            expectation.validateDetectedBackend(backendType)?.let { reason ->
                return@install Result.Incompatible(reason)
            }
            writeCompletionMarker(staging, backendType)
            ModelMetadataStore.write(staging, metadata)
            Result.Installed(staging.name, backendType)
        }
    }

    suspend fun installSd15Checkpoint(
        downloadedFile: File,
        requestedModelId: String,
        metadata: ModelMetadata = ModelMetadata(),
        onProgress: (String) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        install(requestedModelId) { staging ->
            onProgress("Preparing Stable Diffusion 1.5 conversion")
            copyFileCancellable(
                downloadedFile,
                File(staging, "model.safetensors"),
                overwrite = false,
            )
            copyAssetsRecursively("cvtbase", staging)

            val clipSource = File(staging, "clip_skip_1.mnn")
            if (!clipSource.isFile) {
                return@install Result.Failed("Missing bundled CLIP conversion asset")
            }
            copyFileCancellable(
                clipSource,
                File(staging, "clip_v2.mnn"),
                overwrite = true,
            )

            onProgress("Converting model")
            val executable = File(
                context.applicationInfo.nativeLibraryDir,
                EXECUTABLE_NAME,
            )
            if (!executable.isFile) {
                return@install Result.Failed("Native model converter is unavailable")
            }
            val exitCode = runConverter(executable, staging, onProgress)
            if (exitCode != 0 || !File(staging, "finished").isFile) {
                return@install Result.Incompatible(
                    "Only single-file Stable Diffusion 1.5 checkpoints are supported",
                )
            }
            cleanupConversionInputs(staging)
            if (!hasRequiredFiles(staging, "sd15cpu")) {
                return@install Result.Incompatible("Converted model is incomplete")
            }
            ModelMetadataStore.write(staging, metadata)
            Result.Installed(staging.name, "sd15cpu")
        }
    }

    suspend fun installDirectory(
        manifest: CatalogDownloadManifest,
        requestedModelId: String,
        metadata: ModelMetadata = ModelMetadata(),
        onProgress: (String) -> Unit = {},
        downloadFile: suspend (CatalogDownloadFile, File) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        install(requestedModelId) { staging ->
            manifest.files.forEachIndexed { index, file ->
                currentCoroutineContext().ensureActive()
                onProgress("Downloading ${index + 1}/${manifest.files.size}: ${file.targetName}")
                downloadFile(file, File(staging, file.targetName))
            }
            val backendType = detectBackendType(staging)
                ?: return@install Result.Incompatible(
                    "The repository does not contain a complete supported model",
                )
            if (backendType != manifest.backendType) {
                return@install Result.Incompatible("Directory backend does not match its manifest")
            }
            writeCompletionMarker(staging, backendType)
            ModelMetadataStore.write(staging, metadata)
            Result.Installed(staging.name, backendType)
        }
    }

    /**
     * Runs a manual import in the same transaction used by repository downloads.
     * The callback may only prepare and validate [staging]; this installer owns
     * target resolution and publishes the directory without replacing an
     * existing model.
     */
    internal suspend fun installPrepared(
        requestedModelId: String,
        prepare: suspend (File) -> Result,
    ): Result = withContext(Dispatchers.IO) {
        install(requestedModelId, prepare)
    }

    private suspend inline fun install(
        requestedModelId: String,
        prepare: suspend (File) -> Result,
    ): Result {
        currentCoroutineContext().ensureActive()
        val modelId = LocalModelId.normalize(
            requestedModelId,
            ModelRepository.reservedModelIds(),
        )
            ?: return Result.Failed("Invalid model id")
        val modelsDir = ModelStorage.requireModelsDir(context)
        val target = File(modelsDir, modelId)
        if (target.exists()) return Result.AlreadyInstalled(modelId)

        // Staging and the final repository must be on the same public volume;
        // publishing by rename is otherwise not atomic and can fail outright.
        val stagingRoot = ModelStorage.requireStagingDir(context)
        val staging = File(stagingRoot, "$modelId-${UUID.randomUUID()}")
        if (!staging.mkdirs()) {
            stagingRoot.delete()
            return Result.Failed("Could not create model staging directory")
        }
        return try {
            when (val prepared = prepare(staging)) {
                is Result.Installed -> {
                    // Publishing is the commit point. Cancellation is checked
                    // immediately before it; after a successful rename the
                    // complete target is retained because rollback could touch
                    // a concurrently observed or externally replaced model.
                    currentCoroutineContext().ensureActive()
                    when (ModelInstallPublisher.publish(staging, target)) {
                        ModelInstallPublisher.Outcome.ALREADY_INSTALLED -> {
                            return Result.AlreadyInstalled(modelId)
                        }

                        ModelInstallPublisher.Outcome.FAILED -> {
                            return Result.Failed("Could not publish the installed model")
                        }

                        ModelInstallPublisher.Outcome.PUBLISHED -> Unit
                    }
                    Result.Installed(modelId, prepared.backendType)
                }

                else -> prepared
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Model installation failed", e)
            Result.Failed(e.message ?: "Model installation failed")
        } finally {
            if (staging.exists()) staging.deleteRecursively()
            stagingRoot.delete()
        }
    }

    private suspend fun extractFlatZip(zipFile: File, destination: File) {
        zipFile.inputStream().use { input ->
            BoundedModelZipExtractor.extractFlat(input, destination)
        }
    }

    private fun detectBackendType(directory: File): String? = PreparedModelValidator.detectCompleteLayout(directory)?.backendType

    private fun hasRequiredFiles(directory: File, backendType: String): Boolean = ModelFileLayouts.forBackend(backendType)?.requiredFiles?.all {
        val file = File(directory, it)
        file.isFile && file.length() > 0L
    } == true

    private fun writeCompletionMarker(directory: File, backendType: String) {
        val marker = ModelFileLayouts.forBackend(backendType)?.completionMarker ?: return
        File(directory, marker).createNewFile()
        if (backendType == "sd15npu") File(directory, "v3").createNewFile()
    }

    private suspend fun copyAssetsRecursively(assetPath: String, targetDirectory: File) {
        currentCoroutineContext().ensureActive()
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            val target = File(targetDirectory, assetPath.substringAfterLast('/'))
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output ->
                    copyStreamCancellable(input, output)
                }
            }
            return
        }
        children.forEach { child ->
            currentCoroutineContext().ensureActive()
            val childPath = "$assetPath/$child"
            val grandchildren = context.assets.list(childPath).orEmpty()
            if (grandchildren.isEmpty()) {
                val target = File(targetDirectory, child)
                context.assets.open(childPath).use { input ->
                    target.outputStream().use { output ->
                        copyStreamCancellable(input, output)
                    }
                }
            } else {
                val childTarget = File(targetDirectory, child).apply { mkdirs() }
                copyAssetsRecursively(childPath, childTarget)
            }
        }
    }

    private fun cleanupConversionInputs(directory: File) {
        listOf(
            "model.safetensors",
            "clip_skip_1.mnn",
            "clip_skip_2.mnn",
        ).forEach { File(directory, it).delete() }
    }

    private suspend fun runConverter(
        executable: File,
        staging: File,
        onProgress: (String) -> Unit,
    ): Int = coroutineScope {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val process = ProcessBuilder(
            executable.absolutePath,
            "--convert",
            staging.absolutePath,
        ).apply {
            directory(File(nativeDir))
            redirectErrorStream(true)
            environment()["LD_LIBRARY_PATH"] = listOf(
                nativeDir,
                "/system/lib64",
                "/vendor/lib64",
                "/vendor/lib64/egl",
            ).joinToString(":")
            environment()["DSP_LIBRARY_PATH"] = nativeDir
        }.start()

        // Blocking Process/stream APIs do not observe coroutine cancellation.
        // Destroying the child closes stdout and releases both waiting paths.
        val cancellationWatcher = launch(Dispatchers.Unconfined) {
            try {
                awaitCancellation()
            } finally {
                destroyProcess(process)
            }
        }
        val outputJob = launch(Dispatchers.IO) {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        currentCoroutineContext().ensureActive()
                        Log.i(TAG, "Convert: $line")
                        onProgress(line)
                    }
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                throw e
            }
        }

        try {
            while (!process.waitFor(PROCESS_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                currentCoroutineContext().ensureActive()
            }
            outputJob.join()
            currentCoroutineContext().ensureActive()
            process.exitValue()
        } catch (e: CancellationException) {
            destroyProcess(process)
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            cancellationWatcher.cancel()
            if (process.isAlive) destroyProcess(process)
            outputJob.cancel()
        }
    }

    private suspend fun copyFileCancellable(
        source: File,
        target: File,
        overwrite: Boolean,
    ) {
        currentCoroutineContext().ensureActive()
        if (!overwrite && target.exists()) {
            throw IllegalStateException("Destination already exists: ${target.name}")
        }
        source.inputStream().buffered().use { input ->
            target.outputStream().buffered().use { output ->
                copyStreamCancellable(input, output)
            }
        }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun copyStreamCancellable(
        input: InputStream,
        output: OutputStream,
        onChunk: (Int) -> Unit = {},
    ) {
        val coroutineContext = currentCoroutineContext()
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            coroutineContext.ensureActive()
            if (count < 0) break
            onChunk(count)
            output.write(buffer, 0, count)
        }
    }

    private fun destroyProcess(process: Process) {
        if (process.isAlive) {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
        }
        runCatching { process.inputStream.close() }
        runCatching { process.outputStream.close() }
        runCatching { process.errorStream.close() }
    }

    companion object {
        private const val TAG = "ModelInstaller"
        private const val EXECUTABLE_NAME = "libstable_diffusion_core.so"
        private const val COPY_BUFFER_BYTES = 32 * 1024
        private const val PROCESS_POLL_MILLIS = 250L
    }
}

/** Serializes the only step allowed to make a prepared model visible. */
internal object ModelInstallPublisher {
    enum class Outcome {
        PUBLISHED,
        ALREADY_INSTALLED,
        FAILED,
    }

    @Synchronized
    fun publish(staging: File, target: File): Outcome {
        if (!staging.isDirectory) return Outcome.FAILED
        if (target.exists()) return Outcome.ALREADY_INSTALLED
        if (staging.renameTo(target)) return Outcome.PUBLISHED
        return if (target.exists()) Outcome.ALREADY_INSTALLED else Outcome.FAILED
    }
}
