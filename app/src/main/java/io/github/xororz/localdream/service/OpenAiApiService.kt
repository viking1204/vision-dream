package io.github.xororz.localdream.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.xororz.localdream.R
import io.github.xororz.localdream.openai.BoundedSerialExecutor
import io.github.xororz.localdream.openai.OpenAiApiController
import io.github.xororz.localdream.openai.OpenAiApiPreferences
import io.github.xororz.localdream.openai.OpenAiHttpServer
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * User-started foreground service exposing the authenticated LAN image API.
 */
class OpenAiApiService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()

    @Volatile
    private var server: OpenAiHttpServer? = null
    private var executor: BoundedSerialExecutor? = null
    private var controller: OpenAiApiController? = null
    private var startupJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var terminalError: String? = null
    private var backendPrepared = false

    @Volatile
    private var destroyed = false

    @Volatile
    private var acceptQueueUpdates = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (server != null || startupJob?.isActive == true) return START_NOT_STICKY

        terminalError = null
        startForeground(NOTIFICATION_ID, createNotification())
        if (RemoteHostService.isRunning.value) {
            terminalError = getString(R.string.openai_api_host_conflict)
            updateStatus(
                Status(
                    running = false,
                    error = terminalError,
                ),
            )
            stopSelf()
            return START_NOT_STICKY
        }
        if (BackgroundGenerationService.isServiceRunning.value) {
            terminalError = getString(R.string.openai_api_generation_conflict)
            updateStatus(
                Status(
                    running = false,
                    error = terminalError,
                ),
            )
            stopSelf()
            return START_NOT_STICKY
        }
        acquireRuntimeWakeLock()

        val preferences = OpenAiApiPreferences(applicationContext)
        val queueCapacity = preferences.queueCapacity()
        val serialExecutor = BoundedSerialExecutor(
            waitingCapacity = queueCapacity,
            preferredAffinityKey = { BackendService.servingModelId.value },
        )
        val apiController = OpenAiApiController(
            context = applicationContext,
            apiKey = preferences.apiKey(),
            executor = serialExecutor,
            onQueueChanged = { active, queued ->
                if (acceptQueueUpdates) {
                    updateStatus(
                        Status(
                            running = true,
                            ready = true,
                            active = active,
                            queued = queued,
                            queueCapacity = queueCapacity,
                        ),
                    )
                }
            },
        )
        val httpServer = OpenAiHttpServer(
            port = OpenAiApiPreferences.PORT,
            isAuthorized = apiController::isTransportAuthorized,
            handler = apiController::route,
        )
        executor = serialExecutor
        activeExecutor.set(serialExecutor)
        controller = apiController
        acceptQueueUpdates = true

        val preparationVersion = BackendService.openAiPreparation.value.version
        try {
            // Set before BackendService receives standby/start commands so it
            // binds native port 8081 to loopback even if a legacy LAN setting
            // is enabled.
            updateStatus(
                Status(
                    running = true,
                    queueCapacity = queueCapacity,
                ),
            )
            startForegroundService(
                Intent(this, BackendService::class.java)
                    .setAction(BackendService.ACTION_PREPARE_OPENAI_GATEWAY)
                    .putExtra(
                        BackendService.EXTRA_REQUEST_OWNER,
                        BackendService.REQUEST_OWNER_OPENAI_API,
                    ),
            )
            backendPrepared = true
        } catch (e: Exception) {
            failStartup(e)
            return START_NOT_STICKY
        }

        startupJob = serviceScope.launch {
            try {
                val preparation = withTimeout(BACKEND_PREPARE_TIMEOUT_MS) {
                    BackendService.openAiPreparation.first {
                        it.version > preparationVersion
                    }
                }
                if (!preparation.succeeded) {
                    throw IOException(
                        preparation.error ?: "Native backend could not be isolated",
                    )
                }
                if (destroyed) return@launch
                // Publish the LAN listener only after BackendService confirms
                // that no previous process remains reachable on native 8081.
                httpServer.start()
                val shouldClose = synchronized(lifecycleLock) {
                    if (destroyed) {
                        true
                    } else {
                        server = httpServer
                        false
                    }
                }
                if (shouldClose) {
                    httpServer.shutdown()
                } else {
                    updateStatus(
                        Status(
                            running = true,
                            ready = true,
                            queueCapacity = queueCapacity,
                        ),
                    )
                }
            } catch (_: CancellationException) {
                // Normal service teardown.
            } catch (e: Exception) {
                failStartup(e)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        acceptQueueUpdates = false
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
        startupJob?.cancel()
        startupJob = null
        val serverToClose = synchronized(lifecycleLock) {
            destroyed = true
            server.also { server = null }
        }
        serverToClose?.shutdown()
        controller?.cancelActiveCalls()
        controller = null
        val executorToStop = executor
        activeExecutor.compareAndSet(executorToStop, null)
        executorToStop?.shutdownNow()
        executor = null
        updateStatus(Status(error = terminalError))
        if (backendPrepared && BackendService.servingModelId.value == null) {
            backendPrepared = false
            try {
                startService(
                    Intent(this, BackendService::class.java)
                        .setAction(BackendService.ACTION_STOP)
                        .putExtra(
                            BackendService.EXTRA_REQUEST_OWNER,
                            BackendService.REQUEST_OWNER_OPENAI_API,
                        ),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not stop backend after API shutdown", e)
            }
        } else {
            backendPrepared = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireRuntimeWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:openai-api",
            )
            .apply {
                setReferenceCounted(false)
                // This user-started server has no finite timeout. The lock is
                // paired with onDestroy and the persistent stop notification.
                acquire()
            }
    }

    private fun failStartup(error: Exception) {
        if (destroyed) return
        Log.e(TAG, "Could not start OpenAI API service", error)
        acceptQueueUpdates = false
        terminalError = error.message ?: "OpenAI API service could not start"
        updateStatus(Status(running = false, error = terminalError))
        stopSelf()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.openai_api_section),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun createNotification(): Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OpenAiApiService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.openai_api_notify_title))
            .setContentText(
                getString(R.string.openai_api_notify, OpenAiApiPreferences.PORT),
            )
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.openai_api_stop), stopIntent)
            .setOngoing(true)
            .build()
    }

    data class Status(
        val running: Boolean = false,
        val ready: Boolean = false,
        val active: Boolean = false,
        val queued: Int = 0,
        val queueCapacity: Int = OpenAiApiPreferences.DEFAULT_QUEUE_CAPACITY,
        val error: String? = null,
    )

    companion object {
        private const val TAG = "OpenAiApiService"
        private const val CHANNEL_ID = "openai_image_api"
        private const val NOTIFICATION_ID = 5
        private const val BACKEND_PREPARE_TIMEOUT_MS = 60_000L

        const val ACTION_STOP = "io.github.xororz.localdream.STOP_OPENAI_API"

        private val _status = MutableStateFlow(Status())
        val status: StateFlow<Status> = _status

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning
        private val activeExecutor = AtomicReference<BoundedSerialExecutor?>()

        private fun updateStatus(value: Status) {
            _status.value = value
            _isRunning.value = value.running
        }

        /**
         * Exact executor state used by in-app operations to avoid racing a
         * queued API request before the asynchronous status UI recomposes.
         */
        fun hasPendingInference(): Boolean = activeExecutor.get()?.let { executor ->
            executor.hasActiveTask || executor.queuedTaskCount > 0
        } ?: false

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OpenAiApiService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, OpenAiApiService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
