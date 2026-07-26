package io.github.xororz.localdream.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.data.ModelMetadata
import io.github.xororz.localdream.data.ModelMetadataStore
import io.github.xororz.localdream.data.ModelSourceMetadata
import io.github.xororz.localdream.data.ModelStorage
import io.github.xororz.localdream.modelcatalog.BoundedModelZipExtractor
import io.github.xororz.localdream.modelcatalog.CatalogArtifactDownloadLimits
import io.github.xororz.localdream.modelcatalog.CatalogDownloadManifest
import io.github.xororz.localdream.modelcatalog.CatalogInstallExpectation
import io.github.xororz.localdream.modelcatalog.TransactionalModelInstaller
import io.github.xororz.localdream.utils.Http
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

// This foreground-service boundary intentionally owns download lifecycle,
// notification updates, archive validation, and transactional installation.
@Suppress("LargeClass")
class ModelDownloadService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var downloadJob: Job? = null

    @Volatile
    private var activeDownload: ActiveDownload? = null

    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    private val client = Http.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "ModelDownloadService"
        private const val NOTIFICATION_CHANNEL_ID = "model_download_channel"
        private const val NOTIFICATION_ID = 2001

        private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
        val downloadState: StateFlow<DownloadState> = _downloadState

        const val ACTION_START_DOWNLOAD = "action_start_download"
        const val ACTION_CANCEL_DOWNLOAD = "action_cancel_download"

        const val EXTRA_MODEL_ID = "model_id"
        const val EXTRA_MODEL_NAME = "model_name"
        const val EXTRA_FILE_URL = "file_url"
        const val EXTRA_IS_ZIP = "is_zip"
        const val EXTRA_IS_NPU = "is_npu"
        const val EXTRA_MODEL_TYPE = "model_type" // "sd" or "upscaler"
        const val EXTRA_CATALOG_INSTALL_KIND = "catalog_install_kind"
        const val EXTRA_EXPECTED_SHA256 = "expected_sha256"
        const val EXTRA_EXPECTED_SIZE_BYTES = "expected_size_bytes"
        const val EXTRA_CATALOG_DOWNLOAD_MANIFEST = "catalog_download_manifest"
        const val EXTRA_CATALOG_INSTALL_EXPECTATION = "catalog_install_expectation"
        const val EXTRA_MODEL_METADATA_JSON = "model_metadata_json"

        const val INSTALL_KIND_LOCAL_DREAM_ZIP = "local_dream_zip"
        const val INSTALL_KIND_LOCAL_DREAM_DIRECTORY = "local_dream_directory"
        const val INSTALL_KIND_SD15_CHECKPOINT = "sd15_checkpoint"
    }

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(
            val modelId: String,
            val progress: Float,
            val downloadedBytes: Long,
            val totalBytes: Long,
        ) : DownloadState()

        data class Extracting(val modelId: String) : DownloadState()
        data class Installing(val modelId: String, val message: String) : DownloadState()
        data class Success(val modelId: String) : DownloadState()
        data class AlreadyInstalled(val modelId: String) : DownloadState()
        open class Error(
            open val modelId: String,
            open val message: String,
        ) : DownloadState()

        data class Cancelled(
            override val modelId: String,
            override val message: String,
        ) : Error(modelId, message)

        fun isBusy(): Boolean = this is Downloading || this is Extracting || this is Installing
    }

    private data class ActiveDownload(
        val modelId: String,
        val modelName: String,
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID) ?: return START_NOT_STICKY
                val modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: modelId
                val fileUrl = intent.getStringExtra(EXTRA_FILE_URL)
                val isZip = intent.getBooleanExtra(EXTRA_IS_ZIP, false)
                val isNpu = intent.getBooleanExtra(EXTRA_IS_NPU, false)
                val modelType = intent.getStringExtra(EXTRA_MODEL_TYPE) ?: "sd"
                val catalogInstallKind = intent.getStringExtra(EXTRA_CATALOG_INSTALL_KIND)
                val expectedSha256 = intent.getStringExtra(EXTRA_EXPECTED_SHA256)
                val expectedSizeBytes = if (intent.hasExtra(EXTRA_EXPECTED_SIZE_BYTES)) {
                    intent.getLongExtra(EXTRA_EXPECTED_SIZE_BYTES, -1L)
                } else {
                    null
                }
                val catalogManifest = intent.getStringExtra(EXTRA_CATALOG_DOWNLOAD_MANIFEST)
                val catalogInstallExpectation =
                    intent.getStringExtra(EXTRA_CATALOG_INSTALL_EXPECTATION)
                val modelMetadataJson = intent.getStringExtra(EXTRA_MODEL_METADATA_JSON)

                if (fileUrl == null &&
                    catalogInstallKind != INSTALL_KIND_LOCAL_DREAM_DIRECTORY
                ) {
                    return START_NOT_STICKY
                }

                if (_downloadState.value.isBusy()) {
                    Log.w(TAG, "Ignoring concurrent download request for $modelId")
                    return START_NOT_STICKY
                }

                // Terminal states are observable results, not locks. Cancel a
                // pending terminal-state reset before accepting the next model.
                downloadJob?.cancel()
                activeDownload = ActiveDownload(modelId, modelName)
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        createNotification(modelId, modelName, 0f),
                    )
                } catch (e: RuntimeException) {
                    Log.e(TAG, "Unable to enter foreground for model $modelId", e)
                    activeDownload = null
                    _downloadState.value = DownloadState.Error(
                        modelId,
                        getString(
                            R.string.download_service_start_failed,
                            e.message ?: getString(R.string.unknown_error),
                        ),
                    )
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }

                _downloadState.value = DownloadState.Downloading(modelId, 0f, 0, 0)
                startDownload(
                    modelId,
                    modelName,
                    fileUrl,
                    isZip,
                    isNpu,
                    modelType,
                    catalogInstallKind,
                    expectedSha256,
                    expectedSizeBytes,
                    catalogManifest,
                    catalogInstallExpectation,
                    modelMetadataJson,
                    startId,
                )
            }

            ACTION_CANCEL_DOWNLOAD -> {
                cancelDownload(intent.getStringExtra(EXTRA_MODEL_ID), startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startDownload(
        modelId: String,
        modelName: String,
        fileUrl: String?,
        isZip: Boolean,
        isNpu: Boolean,
        modelType: String,
        catalogInstallKind: String?,
        expectedSha256: String?,
        expectedSizeBytes: Long?,
        catalogManifestJson: String?,
        catalogInstallExpectationJson: String?,
        modelMetadataJson: String?,
        serviceStartId: Int,
    ) {
        downloadJob = serviceScope.launch {
            var tempFile: File? = null
            var extractTempDir: File? = null
            try {
                val metadata = modelMetadataJson?.let(ModelMetadata::fromJsonString)
                    ?: ModelMetadata()
                val catalogInstallExpectation = catalogInstallExpectationJson
                    ?.let(CatalogInstallExpectation::fromJsonString)
                val installer = TransactionalModelInstaller(applicationContext)
                var installResult: TransactionalModelInstaller.Result? = null

                if (catalogInstallKind == INSTALL_KIND_LOCAL_DREAM_DIRECTORY) {
                    val manifest = CatalogDownloadManifest.fromJsonString(
                        requireNotNull(catalogManifestJson) {
                            "Directory download manifest is missing"
                        },
                    )
                    val directoryMetadata = if (metadata.source == null) {
                        metadata.copy(
                            source = ModelSourceMetadata(
                                repositoryId = manifest.repositoryId,
                                revision = manifest.revision,
                                artifactKind = INSTALL_KIND_LOCAL_DREAM_DIRECTORY,
                            ),
                        )
                    } else {
                        metadata
                    }
                    var downloadedBeforeFile = 0L
                    installResult = installer.installDirectory(
                        manifest = manifest,
                        requestedModelId = modelId,
                        metadata = directoryMetadata,
                        onProgress = { message ->
                            Log.i(TAG, message)
                        },
                    ) { file, target ->
                        val downloaded = downloadFile(
                            url = file.downloadUrl,
                            destFile = target,
                            modelId = modelId,
                            modelName = modelName,
                            expectedSha256 = file.sha256,
                            expectedSizeBytes = file.sizeBytes,
                            maximumFileBytes = file.sizeBytes,
                            progressOffsetBytes = downloadedBeforeFile,
                            aggregateTotalBytes = manifest.declaredTotalBytes,
                            maximumAggregateBytes = CatalogDownloadManifest.MAX_DECLARED_BYTES,
                        )
                        downloadedBeforeFile += downloaded
                        require(downloadedBeforeFile <= CatalogDownloadManifest.MAX_DECLARED_BYTES) {
                            "Directory model is too large"
                        }
                    }
                } else {
                    val tempDir = File(filesDir, "temp_downloads")
                    if (tempDir.exists()) {
                        tempDir.deleteRecursively()
                    }
                    tempDir.mkdirs()
                    val downloadedArtifact =
                        File(tempDir, "${modelId}_${System.currentTimeMillis()}.tmp")
                    tempFile = downloadedArtifact

                    val maximumArtifactBytes = when (catalogInstallKind) {
                        INSTALL_KIND_LOCAL_DREAM_ZIP,
                        INSTALL_KIND_SD15_CHECKPOINT,
                        -> CatalogArtifactDownloadLimits.maximumBytes(expectedSizeBytes)

                        else -> null
                    }
                    downloadFile(
                        url = requireNotNull(fileUrl),
                        destFile = downloadedArtifact,
                        modelId = modelId,
                        modelName = modelName,
                        expectedSha256 = expectedSha256,
                        expectedSizeBytes = expectedSizeBytes,
                        maximumFileBytes = maximumArtifactBytes,
                    )

                    if (catalogInstallKind != null) {
                        installResult = when (catalogInstallKind) {
                            INSTALL_KIND_LOCAL_DREAM_ZIP -> installer.installZip(
                                downloadedFile = downloadedArtifact,
                                requestedModelId = modelId,
                                expectation = requireNotNull(catalogInstallExpectation) {
                                    "Catalog ZIP install expectation is missing"
                                },
                                metadata = metadata,
                                onProgress = { message ->
                                    _downloadState.value =
                                        DownloadState.Installing(modelId, message)
                                    updateNotification(
                                        modelId,
                                        modelName,
                                        0f,
                                        isExtracting = true,
                                    )
                                },
                            )

                            INSTALL_KIND_SD15_CHECKPOINT -> installer.installSd15Checkpoint(
                                downloadedFile = downloadedArtifact,
                                requestedModelId = modelId,
                                metadata = metadata,
                                onProgress = { message ->
                                    _downloadState.value =
                                        DownloadState.Installing(modelId, message)
                                    updateNotification(
                                        modelId,
                                        modelName,
                                        0f,
                                        isExtracting = true,
                                    )
                                },
                            )

                            else -> TransactionalModelInstaller.Result.Failed(
                                "Unsupported catalog artifact",
                            )
                        }
                    } else {
                        when (modelType) {
                            "sd" -> {
                                if (isZip) {
                                    val modelDir = File(getModelsDir(), modelId)
                                    extractTempDir = File(
                                        ModelStorage.requireStagingDir(this@ModelDownloadService),
                                        "${modelId}_${System.currentTimeMillis()}_extract",
                                    )
                                    extractTempDir.mkdirs()

                                    _downloadState.value = DownloadState.Extracting(modelId)
                                    updateNotification(
                                        modelId,
                                        modelName,
                                        0f,
                                        isExtracting = true,
                                    )

                                    unzipFile(downloadedArtifact, extractTempDir)

                                    if (isNpu) {
                                        File(extractTempDir, "v3").createNewFile()
                                    }
                                    ModelMetadataStore.write(extractTempDir, metadata)
                                    currentCoroutineContext().ensureActive()
                                    if (modelDir.exists()) {
                                        modelDir.deleteRecursively()
                                    }
                                    check(extractTempDir.renameTo(modelDir)) {
                                        "Could not publish the downloaded model"
                                    }
                                    extractTempDir = null
                                }
                            }

                            "upscaler" -> {
                                val upscalerDir = File(getModelsDir(), modelId).apply {
                                    if (!exists()) mkdirs()
                                }
                                val targetFile = File(upscalerDir, Model.UPSCALER_FILE_NAME)

                                if (targetFile.exists()) {
                                    targetFile.delete()
                                }

                                currentCoroutineContext().ensureActive()
                                if (!downloadedArtifact.renameTo(targetFile)) {
                                    copyFileCancellable(downloadedArtifact, targetFile)
                                }
                            }
                        }
                    }
                }

                installResult?.let { result ->
                    when (result) {
                        is TransactionalModelInstaller.Result.Installed -> Unit

                        is TransactionalModelInstaller.Result.AlreadyInstalled -> {
                            tempFile?.delete()
                            tempFile = null
                            _downloadState.value = DownloadState.AlreadyInstalled(modelId)
                            clearActiveDownload(modelId)
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelfResult(serviceStartId)
                            return@launch
                        }

                        is TransactionalModelInstaller.Result.Incompatible ->
                            throw IllegalArgumentException(result.reason)

                        is TransactionalModelInstaller.Result.Failed ->
                            throw IOException(result.reason)
                    }
                }

                currentCoroutineContext().ensureActive()
                tempFile?.delete()
                tempFile = null

                _downloadState.value = DownloadState.Success(modelId)
                clearActiveDownload(modelId)
                updateNotification(modelId, modelName, 100f, success = true)

                withContext(Dispatchers.Main) {
                    kotlinx.coroutines.delay(2000)
                    if (_downloadState.value is DownloadState.Success) {
                        _downloadState.value = DownloadState.Idle
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(serviceStartId)
                }
            } catch (e: CancellationException) {
                // Cancellation (service reclaimed, a new download started, or
                // explicit cancel) is not a download failure: re-throw so it is
                // not surfaced as an "Error" state. Emitting Error here is what
                // produced the spurious "Job was cancelled" snackbar that could
                // appear right after a successful download finished.
                tempFile?.delete()
                extractTempDir?.deleteRecursively()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)

                tempFile?.delete()
                extractTempDir?.deleteRecursively()

                _downloadState.value =
                    DownloadState.Error(modelId, e.message ?: getString(R.string.unknown_error))
                clearActiveDownload(modelId)
                updateNotification(modelId, modelName, 0f, error = e.message)

                withContext(Dispatchers.Main) {
                    kotlinx.coroutines.delay(3000)
                    if (_downloadState.value is DownloadState.Error) {
                        _downloadState.value = DownloadState.Idle
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(serviceStartId)
                }
            }
        }
    }

    private suspend fun downloadFile(
        url: String,
        destFile: File,
        modelId: String,
        modelName: String,
        expectedSha256: String?,
        expectedSizeBytes: Long? = null,
        maximumFileBytes: Long? = null,
        progressOffsetBytes: Long = 0L,
        aggregateTotalBytes: Long? = null,
        maximumAggregateBytes: Long? = null,
    ): Long = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .build()

        val call = client.newCall(request)
        val cancellationWatcher =
            CoroutineScope(currentCoroutineContext()).launch(Dispatchers.Unconfined) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception(
                        getString(R.string.error_download_failed, response.code.toString()),
                    )
                }

                val body = response.body ?: throw Exception("Response body is null")
                val totalBytes = body.contentLength()
                if (maximumFileBytes != null && totalBytes > maximumFileBytes) {
                    throw IOException("Catalog artifact exceeds the download limit")
                }
                if (expectedSizeBytes != null &&
                    totalBytes > 0L &&
                    totalBytes != expectedSizeBytes
                ) {
                    throw IOException("Downloaded file size does not match its manifest")
                }
                var downloadedBytes = 0L
                var lastUpdateTime = 0L
                val digest = expectedSha256?.let { MessageDigest.getInstance("SHA-256") }
                val coroutineContext = currentCoroutineContext()

                java.io.BufferedOutputStream(FileOutputStream(destFile)).use { output ->
                    body.byteStream().buffered().use { input ->
                        val buffer = ByteArray(32 * 1024)

                        while (true) {
                            coroutineContext.ensureActive()
                            val bytes = input.read(buffer)
                            coroutineContext.ensureActive()
                            if (bytes < 0) break
                            val byteCount = bytes.toLong()
                            if (maximumFileBytes != null &&
                                downloadedBytes > maximumFileBytes - byteCount
                            ) {
                                throw IOException("Catalog artifact exceeds the download limit")
                            }
                            val exceedsAggregateLimit = maximumAggregateBytes?.let { maximum ->
                                progressOffsetBytes > maximum ||
                                    downloadedBytes > maximum - progressOffsetBytes - byteCount
                            } == true
                            if (exceedsAggregateLimit) {
                                throw IOException("Directory model is too large")
                            }
                            output.write(buffer, 0, bytes)
                            digest?.update(buffer, 0, bytes)
                            downloadedBytes += byteCount

                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastUpdateTime >= 500 ||
                                downloadedBytes == totalBytes
                            ) {
                                lastUpdateTime = currentTime
                                val reportedTotal = when {
                                    aggregateTotalBytes != null -> aggregateTotalBytes
                                    maximumAggregateBytes != null -> 0L
                                    else -> totalBytes
                                }
                                val reportedDownloaded = progressOffsetBytes + downloadedBytes
                                val progress = if (reportedTotal > 0) {
                                    reportedDownloaded.toFloat() / reportedTotal
                                } else {
                                    0f
                                }

                                _downloadState.value = DownloadState.Downloading(
                                    modelId,
                                    progress,
                                    reportedDownloaded,
                                    reportedTotal,
                                )

                                updateNotification(modelId, modelName, progress)
                            }
                        }
                    }
                }

                coroutineContext.ensureActive()

                // Guard against silently truncated downloads: a dropped connection
                // ends the read loop without throwing, leaving a partial file.
                if (totalBytes > 0 && downloadedBytes != totalBytes) {
                    throw Exception(
                        getString(R.string.error_download_failed, "$downloadedBytes/$totalBytes"),
                    )
                }
                if (expectedSizeBytes != null && downloadedBytes != expectedSizeBytes) {
                    throw IOException("Downloaded file size does not match its manifest")
                }
                if (digest != null) {
                    val actual = digest.digest().joinToString("") {
                        (it.toInt() and 0xff).toString(16).padStart(2, '0')
                    }
                    if (!actual.equals(expectedSha256, ignoreCase = true)) {
                        throw Exception("Downloaded model checksum does not match")
                    }
                }
                downloadedBytes
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            cancellationWatcher.cancel()
        }
    }

    private suspend fun unzipFile(zipFile: File, destDir: File) = withContext(Dispatchers.IO) {
        zipFile.inputStream().use { input ->
            BoundedModelZipExtractor.extractFlat(input, destDir)
        }
    }

    private suspend fun copyStreamCancellable(input: InputStream, output: OutputStream) {
        val coroutineContext = currentCoroutineContext()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            coroutineContext.ensureActive()
            if (count < 0) break
            output.write(buffer, 0, count)
        }
    }

    private suspend fun copyFileCancellable(source: File, target: File) {
        source.inputStream().buffered().use { input ->
            target.outputStream().buffered().use { output ->
                copyStreamCancellable(input, output)
            }
        }
        currentCoroutineContext().ensureActive()
    }

    private fun cancelDownload(requestedModelId: String?, cancellationStartId: Int) {
        val active = activeDownload
        if (active == null) {
            stopSelfResult(cancellationStartId)
            return
        }
        if (requestedModelId != null && requestedModelId != active.modelId) {
            Log.w(
                TAG,
                "Ignoring stale cancellation for $requestedModelId; active=${active.modelId}",
            )
            return
        }

        downloadJob?.cancel()
        _downloadState.value = DownloadState.Cancelled(
            active.modelId,
            getString(R.string.download_cancelled),
        )
        clearActiveDownload(active.modelId)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(cancellationStartId)
    }

    private fun clearActiveDownload(modelId: String) {
        if (activeDownload?.modelId == modelId) {
            activeDownload = null
        }
    }

    private fun getModelsDir(): File = ModelStorage.requireModelsDir(this)

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.model_download_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.model_download_channel_desc)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(
        modelId: String,
        modelName: String,
        progress: Float,
        isExtracting: Boolean = false,
    ): android.app.Notification {
        val title = if (isExtracting) {
            getString(R.string.extracting)
        } else {
            getString(R.string.downloading_model, modelName)
        }

        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val appPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = Intent(this, ModelDownloadService::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
            putExtra(EXTRA_MODEL_ID, modelId)
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            NOTIFICATION_ID,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, (progress * 100).toInt(), isExtracting)
            .setOngoing(true)
            .setContentIntent(appPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.cancel),
                cancelPendingIntent,
            )
            .build()
    }

    private fun updateNotification(
        modelId: String,
        modelName: String,
        progress: Float,
        success: Boolean = false,
        error: String? = null,
        isExtracting: Boolean = false,
    ) {
        val notification = when {
            success -> {
                NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(getString(R.string.download_complete))
                    .setContentText(modelName)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setOngoing(false)
                    .build()
            }

            error != null -> {
                NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(getString(R.string.download_failed))
                    .setContentText(error)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setOngoing(false)
                    .build()
            }

            else -> {
                createNotification(modelId, modelName, progress, isExtracting)
            }
        }

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        handleTimeout(startId, 0)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        handleTimeout(startId, fgsType)
    }

    private fun handleTimeout(startId: Int, fgsType: Int) {
        Log.e(TAG, "Foreground service timeout (fgsType=$fgsType)")
        val active = activeDownload
        downloadJob?.cancel()
        if (active != null) {
            _downloadState.value = DownloadState.Error(
                active.modelId,
                getString(R.string.download_service_timeout),
            )
            clearActiveDownload(active.modelId)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    override fun onDestroy() {
        val active = activeDownload
        serviceScope.cancel()
        if (active != null && _downloadState.value.isBusy()) {
            _downloadState.value = DownloadState.Cancelled(
                active.modelId,
                getString(R.string.download_cancelled),
            )
            clearActiveDownload(active.modelId)
        }
        super.onDestroy()
    }
}
