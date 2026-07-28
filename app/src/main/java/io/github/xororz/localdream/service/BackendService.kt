package io.github.xororz.localdream.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.data.ModelMetadataStore
import io.github.xororz.localdream.data.ModelUsageRanking
import io.github.xororz.localdream.data.RuntimeCompatibilityEvaluator
import io.github.xororz.localdream.data.RuntimeProbe
import io.github.xororz.localdream.data.RuntimeProbeEvaluator
import io.github.xororz.localdream.data.RuntimeProbeInput
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BackendService : Service() {
    @Volatile
    private var process: Process? = null

    // Set true around an intentional teardown (and reset just before a new
    // start) so the monitor thread doesn't surface the resulting process exit
    // as a backend Error. backendState is a process-wide StateFlow shared
    // across Service instances, so a stale Error would otherwise be read by
    // the next model's health check and shown as "backend start failed".
    @Volatile
    private var stopping = false

    // Desired-state reconciliation. `desired` is the config the screen wants
    // running (null = nothing); `serving` is what the live process was actually
    // started for. Both are touched only on the single backend thread via
    // reconcile(), so no extra locking is needed. idleStopJob is the pending
    // grace-period teardown scheduled by a stop request.
    private var desired: BackendConfig? = null
    private var serving: BackendConfig? = null
    private var idleStopJob: Job? = null
    private lateinit var runtimeDir: File

    @Volatile
    private var runtimeDirReady = false

    // The checked-in/prebuilt native core used by release APKs predates the
    // unified --type/--model_dir CLI, while locally rebuilt cores use it.
    // Probe once per Service instance and build the matching command instead
    // of coupling the Kotlin service to whichever binary happened to be
    // packaged.
    private var unifiedNativeCli: Boolean? = null

    // All backend process management (asset copies, exec, destroy/waitFor)
    // runs on this single thread: jobs stay ordered relative to each other
    // and the main thread never blocks on waitFor() or large file copies.
    private val backendDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "backend-control") }
            .asCoroutineDispatcher()
    private val serviceScope = CoroutineScope(SupervisorJob() + backendDispatcher)

    companion object {
        private const val TAG = "BackendService"
        private const val EXECUTABLE_NAME = "libstable_diffusion_core.so"
        private const val RUNTIME_DIR = "runtime_libs"
        private const val RUNTIME_MANIFEST_ASSET = "qairt-runtime-manifest.json"
        private const val HTP_TARGET = "v79"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "backend_service_channel"

        // Grace window before a stop request actually tears the backend down.
        // A re-entry within this window (same or different model) cancels the
        // teardown and reconciles in-place, so quick back-then-reopen reuses
        // the live process and model switches stay on one Service instance
        // (single-threaded, no cross-instance start/stop race). Affects only
        // reuse/latency, never correctness: a slower re-entry just starts fresh.
        private const val IDLE_GRACE_MS = 1500L
        private const val FORCE_STOP_TIMEOUT_SECONDS = 5L
        private const val RUNTIME_READY_POLL_MS = 250L
        private const val RUNTIME_READY_ATTEMPTS = 240
        private val TILE_PROGRESS_REGEX = Regex("""Processed tile (\d+)/(\d+)""")
        private val PROCESS_PID_REGEX = Regex("""pid=(\d+)""")

        const val ACTION_STOP = "io.github.xororz.localdream.STOP_GENERATION"
        const val ACTION_RESTART = "io.github.xororz.localdream.RESTART_BACKEND"

        // Brings the service up as a foreground service without touching any
        // backend process. Sent when host mode starts (while the app is still
        // in the foreground) so later remote /select commands are plain
        // startService() deliveries to an already-foreground service instead
        // of new-FGS starts, which Android 12+ blocks from the background.
        const val ACTION_STANDBY = "io.github.xororz.localdream.STANDBY_BACKEND"
        const val ACTION_PREPARE_OPENAI_GATEWAY =
            "io.github.xororz.localdream.PREPARE_OPENAI_GATEWAY"
        const val EXTRA_REQUEST_OWNER = "request_owner"
        const val EXTRA_IMAGE_INPUT_ENABLED = "image_input_enabled"
        const val EXTRA_SDXL_LOW_RAM = "sdxl_low_ram"
        const val EXTRA_ANIMA_LOW_RAM = "anima_low_ram"
        const val EXTRA_ANIMA_SEQUENTIAL_DIT = "anima_sequential_dit"
        const val EXTRA_EXPECTED_MODEL_ID = "expected_model_id"
        const val REQUEST_OWNER_OPENAI_API = "openai_api"

        // Pseudo backend type: native process in --upscaler_mode (no model
        // dir). Used by host mode so a controller's standalone upscale page
        // can run on this device's NPU.
        const val BACKEND_TYPE_UPSCALER = "upscaler"

        /**
         * API commands must declare image-input intent explicitly and are
         * independent from the in-app img2img switch. Commands from the UI
         * retain the legacy preference fallback when no override is supplied.
         */
        internal fun resolveImageInputRequested(
            requestOwner: String?,
            explicitOverride: Boolean?,
            localPreference: Boolean,
        ): Boolean = if (requestOwner == REQUEST_OWNER_OPENAI_API) {
            explicitOverride == true
        } else {
            explicitOverride ?: localPreference
        }

        private object StateHolder {
            val _backendState = MutableStateFlow<BackendState>(BackendState.Idle)

            // modelId the live process is serving (null when none). Process-wide
            // so a screen can tell whether 8081 is already serving *its* model
            // vs. a previous model still alive in the stop grace window.
            val _servingModelId = MutableStateFlow<String?>(null)

            // Resolution the live process was started for. Reported through
            // host mode's /status so a controller only declares Ready once the
            // backend matches its full config, not just the model id.
            val _servingResolution = MutableStateFlow<Pair<Int, Int>?>(null)
            val _servingImageInputEnabled = MutableStateFlow<Boolean?>(null)

            // Monotonic acknowledgement for API startup. Waiting for a new
            // value prevents port 8809 from opening while an old native
            // process is still bound to 0.0.0.0.
            val _openAiPreparation = MutableStateFlow(OpenAiPreparation())
            val _commandResult = MutableStateFlow(BackendCommandResult())
            val _currentLog = MutableStateFlow("")
            val _tileProgress = MutableStateFlow<TileProgress?>(null)
            val _runtimeProbe = MutableStateFlow(RuntimeProbe(status = io.github.xororz.localdream.data.RuntimeProbeStatus.UNAVAILABLE))
        }

        val backendState: StateFlow<BackendState> = StateHolder._backendState

        val servingModelId: StateFlow<String?> = StateHolder._servingModelId

        val servingResolution: StateFlow<Pair<Int, Int>?> = StateHolder._servingResolution

        val servingImageInputEnabled: StateFlow<Boolean?> =
            StateHolder._servingImageInputEnabled

        val openAiPreparation: StateFlow<OpenAiPreparation> = StateHolder._openAiPreparation
        val commandResult: StateFlow<BackendCommandResult> = StateHolder._commandResult

        val currentLog: StateFlow<String> = StateHolder._currentLog

        val tileProgress: StateFlow<TileProgress?> = StateHolder._tileProgress
        val runtimeProbe: StateFlow<RuntimeProbe> = StateHolder._runtimeProbe

        private fun updateState(state: BackendState) {
            StateHolder._backendState.value = state
        }

        private fun updateServing(config: BackendConfig?) {
            StateHolder._servingModelId.value = config?.modelId
            StateHolder._servingResolution.value = config?.let { Pair(it.width, it.height) }
            StateHolder._servingImageInputEnabled.value = config?.imageInputEnabled
        }

        private fun publishCommandResult(
            modelId: String?,
            error: String?,
        ) {
            val previous = StateHolder._commandResult.value
            StateHolder._commandResult.value = BackendCommandResult(
                version = previous.version + 1L,
                modelId = modelId,
                error = error,
            )
        }

        fun clearProgress() {
            StateHolder._currentLog.value = ""
            StateHolder._tileProgress.value = null
        }
    }

    sealed class BackendState {
        object Idle : BackendState()
        object Starting : BackendState()
        object Running : BackendState()

        // modelId is the model this failure pertains to, or null for a failure
        // that affects any model (e.g. runtime preparation). Lets a screen
        // ignore an error left over from a *different* model's process (a crash
        // in the stop grace window) instead of mistaking it for its own.
        data class Error(val message: String, val modelId: String? = null) : BackendState()
    }

    data class OpenAiPreparation(
        val version: Long = 0L,
        val succeeded: Boolean = true,
        val error: String? = null,
    )

    data class BackendCommandResult(
        val version: Long = 0L,
        val modelId: String? = null,
        val error: String? = null,
    )

    data class TileProgress(val current: Int, val total: Int)

    // What a backend process is (or should be) running for. Equality drives
    // reconcile()'s "already serving this exact config" decision. listenOnAll
    // is part of the config on purpose: entering/exiting host mode changes the
    // required bind address, and reusing a live process across that boundary
    // would either leave the port unreachable for the controller or leave it
    // exposed after host mode ends.
    private data class BackendConfig(
        val modelId: String,
        val backendType: String,
        val width: Int,
        val height: Int,
        val listenOnAll: Boolean,
        val imageInputEnabled: Boolean,
        val sdxlLowRam: Boolean,
        val animaLowRam: Boolean,
        val animaSequentialDit: Boolean,
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceScope.launch { prepareRuntimeDir() }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "service command: ${intent?.action}")
        val openAiGatewayOwnsBackend = OpenAiApiService.isRunning.value
        try {
            val notification = createNotification(this.getString(R.string.backend_notify))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val foregroundType = if (openAiGatewayOwnsBackend) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
                startForeground(NOTIFICATION_ID, notification, foregroundType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Android 12+ can reject foreground promotion when the command
            // arrived with the app in the background (e.g. the service was
            // reclaimed and re-created by a remote host-mode command).
            // Continue as a background service rather than crash: the backend
            // process still works, the system may just reclaim it sooner.
            Log.w(TAG, "startForeground rejected: ${e.message}")
        }

        // Commands only declare intent; the single backend thread converges the
        // actual process to it via reconcile(). This keeps every start/stop
        // ordered and race-free regardless of how fast the screen comes and goes.
        val requestedByOpenAiGateway =
            intent?.getStringExtra(EXTRA_REQUEST_OWNER) == REQUEST_OWNER_OPENAI_API
        val isLocalRuntimeCommand = intent?.action != ACTION_STANDBY &&
            intent?.action != ACTION_PREPARE_OPENAI_GATEWAY &&
            !requestedByOpenAiGateway
        val requestedModelId = intent?.getStringExtra("modelId")
            ?: intent?.getStringExtra(EXTRA_EXPECTED_MODEL_ID)
        if (openAiGatewayOwnsBackend &&
            isLocalRuntimeCommand &&
            (
                OpenAiApiService.hasPendingInference() ||
                    BackgroundGenerationService.isServiceRunning.value
                )
        ) {
            val message = "An image request is using the backend"
            Log.w(TAG, "Rejecting local backend command: $message")
            publishCommandResult(requestedModelId, message)
            return START_NOT_STICKY
        }
        if (isLocalRuntimeCommand) publishCommandResult(requestedModelId, null)

        when (intent?.action) {
            ACTION_STOP -> serviceScope.launch {
                requestStop(
                    startId = startId,
                    expectedModelId = intent.getStringExtra(EXTRA_EXPECTED_MODEL_ID),
                )
            }

            ACTION_PREPARE_OPENAI_GATEWAY -> serviceScope.launch {
                prepareOpenAiGateway()
            }

            // Foreground promotion only; no backend change.
            ACTION_STANDBY -> {}

            else -> {
                val forceRestart = intent?.action == ACTION_RESTART
                val config = parseConfig(intent)
                serviceScope.launch { requestStart(config, forceRestart) }
            }
        }

        return START_NOT_STICKY
    }

    private fun parseConfig(intent: Intent?): BackendConfig? {
        val modelId = intent?.getStringExtra("modelId") ?: return null
        // Backend type is decided by the caller (it already has the Model);
        // re-deriving it here would require a full model-directory scan.
        val backendType = intent.getStringExtra("backendType") ?: return null
        val width = intent.getIntExtra("width", 512)
        val height = intent.getIntExtra("height", 512)
        // Host mode is read from RemoteHostService's in-process state, not a
        // persisted flag: a crash can never leave a stale "expose the port"
        // bit behind, and a config-equality check below forces a restart when
        // the bind address requirement changes.
        // The authenticated OpenAI gateway must be the only LAN-facing
        // surface while active. Exposing native 8081 as well would bypass its
        // bearer check and bounded queue.
        val listenOnAll = if (OpenAiApiService.isRunning.value) {
            false
        } else {
            getSharedPreferences("app_prefs", MODE_PRIVATE)
                .getBoolean("listen_on_all_addresses", false) ||
                RemoteHostService.isRunning.value
        }
        val explicitImageInput = intent
            .takeIf { it.hasExtra(EXTRA_IMAGE_INPUT_ENABLED) }
            ?.getBooleanExtra(EXTRA_IMAGE_INPUT_ENABLED, false)
        val imageInputRequested = resolveImageInputRequested(
            requestOwner = intent.getStringExtra(EXTRA_REQUEST_OWNER),
            explicitOverride = explicitImageInput,
            localPreference = getSharedPreferences("app_prefs", MODE_PRIVATE)
                .getBoolean("use_img2img", true),
        )
        val imageInputEnabled = backendType != BACKEND_TYPE_UPSCALER &&
            imageInputRequested &&
            hasImageEncoder(modelId, backendType)
        val preferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        return BackendConfig(
            modelId = modelId,
            backendType = backendType,
            width = width,
            height = height,
            listenOnAll = listenOnAll,
            imageInputEnabled = imageInputEnabled,
            sdxlLowRam = intent.takeIf { it.hasExtra(EXTRA_SDXL_LOW_RAM) }
                ?.getBooleanExtra(EXTRA_SDXL_LOW_RAM, true)
                ?: preferences.getBoolean("sdxl_lowram", true),
            animaLowRam = intent.takeIf { it.hasExtra(EXTRA_ANIMA_LOW_RAM) }
                ?.getBooleanExtra(EXTRA_ANIMA_LOW_RAM, true)
                ?: preferences.getBoolean("anima_lowram", true),
            animaSequentialDit = intent.takeIf { it.hasExtra(EXTRA_ANIMA_SEQUENTIAL_DIT) }
                ?.getBooleanExtra(EXTRA_ANIMA_SEQUENTIAL_DIT, false)
                ?: preferences.getBoolean("anima_seq_dit", false),
        )
    }

    private fun hasImageEncoder(modelId: String, backendType: String): Boolean {
        val encoderName = when (backendType) {
            "sd15cpu" -> "vae_encoder.mnn"
            "sd15npu", "sdxl", "anima" -> "vae_encoder.bin"
            else -> return false
        }
        val encoder = File(File(Model.getModelsDir(this), modelId), encoderName)
        return encoder.isFile && encoder.length() > 0L
    }

    // Declares the desired backend and converges to it. Cancels any pending
    // idle teardown first so a quick re-entry keeps the live process.
    private fun requestStart(config: BackendConfig?, forceRestart: Boolean) {
        if (config == null) {
            updateState(BackendState.Error("Model not found"))
            return
        }
        idleStopJob?.cancel()
        idleStopJob = null
        desired = config
        reconcile(forceRestart)
    }

    /**
     * Removes any pre-existing native listener before the authenticated API is
     * published. The service remains foreground and ready for the first API
     * model request.
     */
    private fun prepareOpenAiGateway() {
        idleStopJob?.cancel()
        idleStopJob = null
        // A loopback-only process is already isolated from LAN clients and can
        // remain loaded while the gateway starts. Only a legacy LAN-bound
        // process must be torn down before port 8809 is published.
        val requiresStop = process?.isAlive == true && serving?.listenOnAll == true
        val stopped = if (requiresStop) {
            desired = null
            stopBackend()
        } else {
            true
        }
        if (stopped && process?.isAlive != true && runtimeDirReady) {
            updateState(BackendState.Idle)
        }
        val previous = StateHolder._openAiPreparation.value
        StateHolder._openAiPreparation.value = OpenAiPreparation(
            version = previous.version + 1L,
            succeeded = stopped,
            error = if (stopped) null else "Native backend did not stop",
        )
    }

    // Declares that nothing should run, but only tears down after a grace
    // window. A re-entry within the window cancels this job and reconciles in
    // place; otherwise the process is stopped and the Service stops itself.
    private fun requestStop(
        startId: Int,
        expectedModelId: String?,
    ) {
        if (expectedModelId != null && serving?.modelId != expectedModelId) {
            Log.i(
                TAG,
                "Ignoring stale unload for $expectedModelId; serving ${serving?.modelId}",
            )
            return
        }
        desired = null
        idleStopJob?.cancel()
        idleStopJob = serviceScope.launch {
            delay(IDLE_GRACE_MS)
            // A re-entry during the delay normally cancels us. The startId guard
            // closes the remaining edge where a new command's onStartCommand
            // raced in just as the grace fired: stopSelfResult() refuses to stop
            // when a newer start exists, leaving the (re)started service alive
            // with its foreground notification intact.
            if (desired == null) {
                stopBackend()
                // While host mode is active the service must survive with its
                // foreground status: remote /select commands arrive over the
                // network with no visible activity, and Android 12+ blocks
                // promoting a freshly started service to the foreground from
                // that state. Keeping this one alive makes those commands
                // plain startService() deliveries to a live FGS.
                if (!RemoteHostService.isRunning.value &&
                    !OpenAiApiService.isRunning.value &&
                    stopSelfResult(startId)
                ) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        }
    }

    // Converges the actual process to `desired`. Runs only on the single
    // backend thread, so reading current state and any start/stop are atomic
    // with respect to other commands.
    private fun reconcile(forceRestart: Boolean) {
        val want = desired ?: return
        // prepareRuntimeDir runs earlier on this same thread and publishes its
        // own error on failure; if it didn't finish ready, leave that state.
        if (!runtimeDirReady) {
            return
        }
        val alreadyServing = process?.isAlive == true && serving == want
        if (alreadyServing && !forceRestart) {
            Log.i(TAG, "backend already serving ${want.modelId} ${want.width}x${want.height}")
            updateServing(want)
            recordModelUsage(want)
            updateState(BackendState.Running)
            return
        }
        if (!stopBackend()) {
            updateState(
                BackendState.Error(
                    "Previous backend did not stop",
                    serving?.modelId,
                ),
            )
            return
        }
        if (startBackend(want)) {
            serving = want
            updateServing(want)
            recordModelUsage(want)
            updateState(BackendState.Running)
        } else {
            serving = null
            updateServing(null)
            updateState(BackendState.Error("Backend start failed", want.modelId))
        }
    }

    private fun recordModelUsage(config: BackendConfig) {
        if (config.backendType != BACKEND_TYPE_UPSCALER) {
            ModelUsageRanking.record(this, config.modelId)
        }
    }

    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        handleTimeout(0)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        handleTimeout(fgsType)
    }

    private fun handleTimeout(fgsType: Int) {
        Log.e(TAG, "Foreground service timeout (fgsType=$fgsType)")
        updateState(BackendState.Error("Service timeout", servingModelId.value))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        serviceScope.launch {
            desired = null
            idleStopJob?.cancel()
            try {
                stopBackend()
            } catch (e: Exception) {
                Log.e(TAG, "stopBackend on timeout failed", e)
            }
        }
    }

    private fun createNotificationChannel() {
        val name = "Backend Service"
        val descriptionText = "Backend service for image generation"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(contentText: String): Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(this.getString(R.string.backend_notify_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun prepareRuntimeDir() {
        try {
            runtimeDir = File(filesDir, RUNTIME_DIR).apply {
                if (!exists()) {
                    mkdirs()
                }
            }

            try {
                val qnnlibsAssets = assets.list("qnnlibs")
                qnnlibsAssets?.forEach { fileName ->
                    val targetLib = File(runtimeDir, fileName)

                    val needsCopy = !targetLib.exists() ||
                        run {
                            val assetInputStream = assets.open("qnnlibs/$fileName")
                            val assetSize = assetInputStream.use { it.available().toLong() }
                            targetLib.length() != assetSize
                        }

                    if (needsCopy) {
                        val assetInputStream = assets.open("qnnlibs/$fileName")
                        assetInputStream.use { input ->
                            targetLib.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        Log.d(TAG, "Copied $fileName from assets to runtime directory")
                    }

                    targetLib.setReadable(true, true)
                    targetLib.setExecutable(true, true)
                }
                Log.i(TAG, "QNN libraries prepared in runtime directory")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to prepare QNN libraries from assets", e)
                throw RuntimeException("Failed to prepare QNN libraries from assets", e)
            }

            runtimeDir.setReadable(true, true)
            runtimeDir.setExecutable(true, true)
            runtimeDirReady = true

            Log.i(TAG, "Runtime directory prepared: ${runtimeDir.absolutePath}")
            Log.i(TAG, "Runtime files: ${runtimeDir.list()?.joinToString()}")
        } catch (e: Exception) {
            Log.e(TAG, "Prepare runtime dir failed", e)
            updateState(BackendState.Error("Prepare runtime dir failed: ${e.message}"))
        }
    }

    private fun startBackend(config: BackendConfig): Boolean {
        val modelId = config.modelId
        val backendType = config.backendType
        val width = config.width
        val height = config.height
        Log.i(TAG, "backend start, model: $modelId, resolution: $width×$height")

        // reconcile() has already stopped any previous process; just re-arm
        // crash reporting for the process we are about to start.
        stopping = false
        updateState(BackendState.Starting)
        StateHolder._currentLog.value = ""
        StateHolder._tileProgress.value = null

        try {
            val nativeDir = applicationInfo.nativeLibraryDir
            val modelsDir = File(Model.getModelsDir(this), modelId)

            val executableFile = File(nativeDir, EXECUTABLE_NAME)

            if (!executableFile.exists()) {
                Log.e(TAG, "error: executable does not exist: ${executableFile.absolutePath}")
                return false
            }

            val contextFingerprint = File(modelsDir, "unet.bin")
                .takeIf(File::isFile)
                ?.let(RuntimeCompatibilityEvaluator::sha256)
            val manifestJson = runCatching {
                assets.open(RUNTIME_MANIFEST_ASSET).bufferedReader().use { it.readText() }
            }.getOrNull()
            val compatibility = RuntimeCompatibilityEvaluator().evaluate(
                manifestJson = manifestJson,
                runtimeDirectory = runtimeDir,
                coreFile = executableFile,
                metadata = ModelMetadataStore.read(modelsDir),
                deviceAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                htpTarget = HTP_TARGET,
                contextFingerprint = contextFingerprint,
            )
            val runtimeManifest = manifestJson?.let { raw ->
                runCatching { io.github.xororz.localdream.data.RuntimeManifest.fromJsonString(raw) }.getOrNull()
            }
            fun publishRuntimeProbe(nativeReady: Boolean?, loadedLibraryFingerprints: Map<String, String> = emptyMap()) {
                StateHolder._runtimeProbe.value = RuntimeProbeEvaluator.evaluate(
                    RuntimeProbeInput(
                        deviceModel = Build.MODEL,
                        soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
                        abi = Build.SUPPORTED_ABIS.firstOrNull(),
                        qairtVersion = runtimeManifest?.qairtVersion,
                        htpTarget = HTP_TARGET,
                        contextFingerprint = contextFingerprint,
                        // Static manifest files only prove what the APK prepared.
                        // VERIFIED is published later from /proc/<pid>/maps after
                        // the process has answered /health.
                        loadedLibraryFingerprints = loadedLibraryFingerprints,
                        compatibility = compatibility,
                        nativeReady = nativeReady,
                    ),
                )
            }
            if (!compatibility.isCompatible) {
                publishRuntimeProbe(nativeReady = false)
                val detail = compatibility.rejections.joinToString(",")
                Log.e(TAG, "RUNTIME_FINGERPRINT_MISMATCH: $detail")
                updateState(
                    BackendState.Error("RUNTIME_FINGERPRINT_MISMATCH: $detail", config.modelId),
                )
                return false
            }
            if (compatibility.requiresCompatibilityFallback) {
                Log.w(TAG, "Model has no runtime metadata; using compatibility fallback")
            }

            val usesUnifiedCli = supportsUnifiedNativeCli(executableFile)
            val command = NativeBackendCommandFactory.build(
                executableFile = executableFile,
                modelsDir = modelsDir,
                runtimeDir = runtimeDir,
                config = NativeBackendLaunchConfig(
                    modelId = config.modelId,
                    backendType = config.backendType,
                    width = config.width,
                    height = config.height,
                    listenOnAll = config.listenOnAll,
                    imageInputEnabled = config.imageInputEnabled,
                    sdxlLowRam = config.sdxlLowRam,
                    animaLowRam = config.animaLowRam,
                    animaSequentialDit = config.animaSequentialDit,
                ),
                usesUnifiedCli = usesUnifiedCli,
            )
            Log.i(
                TAG,
                "Using ${if (usesUnifiedCli) "unified" else "legacy"} native CLI",
            )
            val env = mutableMapOf<String, String>()

            val systemLibPaths = mutableListOf(
                runtimeDir.absolutePath,
                "/system/lib64",
                "/vendor/lib64",
                "/vendor/lib64/egl",
            )
            try {
                val maliSymlink = File("/system/vendor/lib64/egl/libGLES_mali.so")
                if (maliSymlink.exists()) {
                    val realPath = maliSymlink.canonicalPath
                    val soc = realPath.split("/").getOrNull(realPath.split("/").size - 2)

                    if (soc != null) {
                        val socPaths = listOf(
                            "/vendor/lib64/$soc",
                            "/vendor/lib64/egl/$soc",
                        )

                        socPaths.forEach { path ->
                            if (!systemLibPaths.contains(path)) {
                                systemLibPaths.add(path)
                                Log.d("LibPath", "Added SoC path: $path")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("LibPath", "Failed to resolve Mali paths: ${e.message}")
            }
            val systemLibPathsStr = systemLibPaths.joinToString(":")
            env["LD_LIBRARY_PATH"] = systemLibPathsStr
            env["DSP_LIBRARY_PATH"] = runtimeDir.absolutePath

            Log.d(TAG, "COMMAND: ${command.joinToString(" ")}")
            Log.d(TAG, "DIR: $runtimeDir")
            Log.d(TAG, "LD_LIBRARY_PATH=${env["LD_LIBRARY_PATH"]}")
            Log.d(TAG, "DSP_LIBRARY_PATH=${env["DSP_LIBRARY_PATH"]}")

            val processBuilder = ProcessBuilder(command).apply {
                directory(File(nativeDir))
                redirectErrorStream(true)
                environment().putAll(env)
            }

            val proc = processBuilder.start()
            process = proc
            publishRuntimeProbe(nativeReady = null)
            awaitRuntimeEvidence(proc, runtimeDir) { ready, fingerprints ->
                if (process === proc) publishRuntimeProbe(ready, fingerprints)
            }

            startMonitorThread(proc)

            return true
        } catch (e: Exception) {
            StateHolder._runtimeProbe.value = RuntimeProbe(status = io.github.xororz.localdream.data.RuntimeProbeStatus.UNAVAILABLE)
            Log.e(TAG, "backend start failed", e)
            updateState(BackendState.Error("backend start failed: ${e.message}", config.modelId))
            return false
        }
    }

    private fun supportsUnifiedNativeCli(executableFile: File): Boolean {
        unifiedNativeCli?.let { return it }
        val unified = try {
            val probe = ProcessBuilder(
                executableFile.absolutePath,
                "--type",
                "__vision_dream_cli_probe__",
            ).redirectErrorStream(true).start()
            val output = probe.inputStream.bufferedReader().use { it.readText() }
            if (!probe.waitFor(5, TimeUnit.SECONDS)) {
                probe.destroyForcibly()
                throw IOException("native CLI probe timed out")
            }
            output.contains("Invalid --type")
        } catch (e: Exception) {
            // New builds are the forward-compatible default. A failed probe
            // must not silently downgrade their richer model contract.
            Log.w(TAG, "Native CLI probe failed; assuming unified CLI", e)
            true
        }
        unifiedNativeCli = unified
        return unified
    }

    /**
     * A successful ProcessBuilder call is not runtime readiness. Wait for the
     * native listener, then derive library evidence from this exact child's
     * mapped files; absent evidence deliberately remains UNAVAILABLE.
     */
    private fun awaitRuntimeEvidence(
        proc: Process,
        runtimeDirectory: File,
        publish: (Boolean?, Map<String, String>) -> Unit,
    ) {
        Thread {
            repeat(RUNTIME_READY_ATTEMPTS) {
                if (process !== proc || !proc.isAlive) {
                    publish(null, emptyMap())
                    return@Thread
                }
                if (nativeHealthReady()) {
                    val fingerprints = runtimeLibraryFingerprints(proc, runtimeDirectory)
                    publish(if (fingerprints.isNotEmpty()) true else null, fingerprints)
                    return@Thread
                }
                Thread.sleep(RUNTIME_READY_POLL_MS)
            }
            if (process === proc) publish(null, emptyMap())
        }.apply {
            isDaemon = true
            name = "runtime-evidence"
            start()
        }
    }

    private fun nativeHealthReady(): Boolean = runCatching {
        val connection = URL("http://127.0.0.1:8081/health").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = RUNTIME_READY_POLL_MS.toInt()
            connection.readTimeout = RUNTIME_READY_POLL_MS.toInt()
            connection.requestMethod = "GET"
            connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    private fun runtimeLibraryFingerprints(proc: Process, runtimeDirectory: File): Map<String, String> = runCatching {
        val root = runtimeDirectory.canonicalFile
        val pid = PROCESS_PID_REGEX.find(proc.toString())?.groupValues?.get(1)
            ?: return@runCatching emptyMap()
        File("/proc/$pid/maps").useLines { lines ->
            lines.mapNotNull { line ->
                val mappedPath = line.substringAfterLast(' ', missingDelimiterValue = "")
                    .removeSuffix(" (deleted)")
                File(mappedPath).takeIf { file ->
                    file.isFile && file.canonicalPath.startsWith("${root.path}/")
                }
            }.distinctBy { file -> file.canonicalFile.path }.associate { file ->
                file.name to RuntimeCompatibilityEvaluator.sha256(file)
            }
        }
    }.getOrElse {
        Log.w(TAG, "Unable to read actual native library mappings", it)
        emptyMap()
    }

    private fun startMonitorThread(proc: Process) {
        Thread {
            val exitCode = try {
                proc.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val logLine = line!!
                        Log.i(TAG, "Backend: $logLine")
                        // A replaced process can still flush buffered stdout
                        // after the new process cleared its progress state.
                        // Publish only logs belonging to the currently tracked
                        // process so stale tiles cannot overwrite the new run.
                        if (process === proc) {
                            StateHolder._currentLog.value = logLine
                            TILE_PROGRESS_REGEX.find(logLine)?.let { match ->
                                val current = match.groupValues[1].toIntOrNull()
                                val total = match.groupValues[2].toIntOrNull()
                                if (current != null && total != null && total > 0) {
                                    StateHolder._tileProgress.value =
                                        TileProgress(current, total)
                                }
                            }
                        }
                    }
                }
                proc.waitFor()
            } catch (e: Exception) {
                Log.e(TAG, "monitor error", e)
                if (isLiveCrash(proc)) {
                    updateState(BackendState.Error("monitor error: ${e.message}", servingModelId.value))
                }
                return@Thread
            }
            Log.i(TAG, "Backend process exited with code: $exitCode")
            // Only surface as an error when this is still the active process and
            // we didn't intentionally stop it; a torn-down or superseded process
            // exiting is expected and must not poison the shared backendState.
            if (isLiveCrash(proc)) {
                StateHolder._runtimeProbe.value = RuntimeProbe(
                    status = io.github.xororz.localdream.data.RuntimeProbeStatus.UNAVAILABLE,
                )
                updateState(
                    BackendState.Error(
                        "Backend process exited with code: $exitCode",
                        servingModelId.value,
                    ),
                )
            } else {
                Log.i(TAG, "backend exit ($exitCode) was intentional/stale, not reporting")
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    // True when proc is still the tracked process and no intentional stop is in
    // progress, i.e. its exit really is an unexpected crash worth reporting.
    private fun isLiveCrash(proc: Process): Boolean = !stopping && process === proc

    override fun onDestroy() {
        super.onDestroy()
        // The scope is never cancelled, so this job still runs after
        // onDestroy returns; closing the dispatcher afterwards lets its
        // thread wind down once the backend process has exited.
        serviceScope.launch {
            idleStopJob?.cancel()
            stopBackend()
            backendDispatcher.close()
        }
    }

    /**
     * Stops the tracked native process and returns only after its listener can
     * no longer be alive. API preparation must not acknowledge success based
     * solely on sending SIGKILL: doing so could briefly expose the previous
     * unauthenticated LAN listener beside the authenticated gateway.
     */
    private fun stopBackend(): Boolean {
        Log.i(TAG, "to stop backend")
        // Mark the upcoming exit as intentional before destroy() so the monitor
        // thread (which wakes the instant the process dies) won't race ahead and
        // report it as a crash.
        stopping = true
        val proc = process
        if (proc != null) {
            try {
                proc.destroy()

                if (!proc.waitFor(FORCE_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    if (!proc.waitFor(FORCE_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        val message = "Native backend did not exit after forced stop"
                        Log.e(TAG, message)
                        updateState(BackendState.Error(message, serving?.modelId))
                        return false
                    }
                }

                Log.i(TAG, "process end, code: ${proc.exitValue()}")
                updateState(BackendState.Idle)
            } catch (e: Exception) {
                Log.e(TAG, "error", e)
                updateState(BackendState.Error("error: ${e.message}"))
                if (proc.isAlive) return false
            } finally {
                if (!proc.isAlive && process === proc) {
                    process = null
                }
            }
        }
        if (process?.isAlive == true) return false
        StateHolder._runtimeProbe.value = RuntimeProbe(
            status = io.github.xororz.localdream.data.RuntimeProbeStatus.UNAVAILABLE,
        )
        serving = null
        updateServing(null)
        StateHolder._currentLog.value = ""
        StateHolder._tileProgress.value = null
        return true
    }
}
