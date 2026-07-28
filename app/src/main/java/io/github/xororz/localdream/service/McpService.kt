package io.github.xororz.localdream.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.db.AppDatabase
import io.github.xororz.localdream.inference.BackendRuntimeLeaseManager
import io.github.xororz.localdream.inference.InferenceDispatcher
import io.github.xororz.localdream.mcp.AndroidMcpDownloadStore
import io.github.xororz.localdream.mcp.AndroidMcpGenerationScheduler
import io.github.xororz.localdream.mcp.AndroidMcpInstalledModelCatalog
import io.github.xororz.localdream.mcp.AndroidMcpAssetStore
import io.github.xororz.localdream.mcp.AndroidMcpClientManagementStore
import io.github.xororz.localdream.mcp.AndroidMcpPresetStore
import io.github.xororz.localdream.mcp.AndroidMcpPromptStore
import io.github.xororz.localdream.mcp.AndroidMcpRuntimeStore
import io.github.xororz.localdream.mcp.McpClientCredentialStore
import io.github.xororz.localdream.mcp.McpConfirmationStore
import io.github.xororz.localdream.mcp.McpPendingConfirmation
import io.github.xororz.localdream.mcp.McpGenerationGateway
import io.github.xororz.localdream.mcp.McpHistoryImageContentResolver
import io.github.xororz.localdream.mcp.McpHttpServer
import io.github.xororz.localdream.mcp.McpImageCapabilityStore
import io.github.xororz.localdream.mcp.McpLanHostAllowlist
import io.github.xororz.localdream.mcp.McpSessionRegistry
import io.github.xororz.localdream.mcp.McpSseEventStore
import io.github.xororz.localdream.mcp.McpTransport
import io.github.xororz.localdream.mcp.RoomMcpAuditSink
import io.github.xororz.localdream.mcp.RoomMcpJobStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CompletableFuture

/**
 * Independently controlled MCP foreground service. It owns only its listener,
 * credentials and sessions; inference capacity is shared through P3's process
 * dispatcher lease and never through the OpenAI API service.
 */
class McpService : Service() {
    private var loopbackServer: McpHttpServer? = null
    private var lanServer: McpHttpServer? = null
    private var runtimeLease: BackendRuntimeLeaseManager.Lease? = null
    private var scheduler: AndroidMcpGenerationScheduler? = null
    private var lanInputs: LanInputs? = null
    private val sessions = McpSessionRegistry()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.mcp_section), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val port = intent?.getIntExtra(EXTRA_PORT, McpHttpServer.DEFAULT_PORT) ?: McpHttpServer.DEFAULT_PORT
        val lanEnabled = intent?.getBooleanExtra(EXTRA_LAN_ENABLED, false) == true
        if (loopbackServer != null) {
            runCatching { configureLan(lanEnabled, port, lanInputs) }
                .onFailure { updateStatus(Status(running = true, ready = true, port = port, transport = McpTransport.LOOPBACK, error = it.message)) }
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification())
        try {
            val database = AppDatabase.get(applicationContext)
            val lanHostAllowlist = McpLanHostAllowlist(applicationContext)
            val imageCapabilities = McpImageCapabilityStore()
            val sseEvents = McpSseEventStore()
            val jobs = RoomMcpJobStore(database)
            val generationScheduler = AndroidMcpGenerationScheduler(applicationContext, InferenceDispatcher.process, jobs)
            val gateway = McpGenerationGateway(
                jobs = jobs,
                scheduler = generationScheduler,
                capabilities = imageCapabilities,
                models = AndroidMcpInstalledModelCatalog(applicationContext),
                prompts = AndroidMcpPromptStore(applicationContext),
                presets = AndroidMcpPresetStore(applicationContext),
                assets = AndroidMcpAssetStore(applicationContext),
                downloads = AndroidMcpDownloadStore(applicationContext),
                cancellations = generationScheduler,
                runtime = AndroidMcpRuntimeStore(applicationContext),
                clients = AndroidMcpClientManagementStore(applicationContext) { clientId ->
                    sessions.sessionIdsForClient(clientId).forEach(sseEvents::close)
                    sessions.invalidateClient(clientId)
                },
            )
            val listener = McpHttpServer(
                port = port,
                transport = McpTransport.LOOPBACK,
                credentialStore = McpClientCredentialStore(applicationContext),
                sessions = sessions,
                allowedLanHosts = lanHostAllowlist::hosts,
                confirmationStore = confirmations,
                auditSink = RoomMcpAuditSink(database.mcpAuditEventDao()),
                imageCapabilities = imageCapabilities,
                imageResolver = McpHistoryImageContentResolver(applicationContext, database.historyDao()),
                toolGateway = gateway,
                sseEvents = sseEvents,
            )
            listener.start()
            loopbackServer = listener
            scheduler = generationScheduler
            lanInputs = LanInputs(gateway, imageCapabilities, database, generationScheduler, sseEvents)
            runtimeLease = InferenceDispatcher.process.acquireServiceLease(DISPATCH_OWNER)
            val lanError = runCatching { configureLan(lanEnabled, port, lanInputs) }.exceptionOrNull()
            updateStatus(Status(running = true, ready = true, port = port, transport = McpTransport.LOOPBACK, error = lanError?.message))
        } catch (error: Exception) {
            updateStatus(Status(error = error.message ?: "MCP listener could not start", port = port, transport = McpTransport.LOOPBACK))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        loopbackServer?.shutdown()
        loopbackServer = null
        lanServer?.shutdown()
        lanServer = null
        sessions.removeForTransport(McpTransport.LOOPBACK)
        sessions.removeForTransport(McpTransport.LAN)
        val lease = runtimeLease
        runtimeLease = null
        val completion = scheduler?.cancelAll().orEmpty()
        scheduler = null
        lanInputs = null
        // The service lease must outlive an interrupted native call. Job leases
        // also remain held by the dispatcher until the same barriers complete.
        if (completion.isEmpty()) lease?.close() else CompletableFuture.allOf(*completion.toTypedArray())
            .whenComplete { _, _ -> lease?.close() }
        confirmations.clear()
        updateStatus(Status())
        super.onDestroy()
    }

    private fun configureLan(
        enabled: Boolean,
        port: Int,
        inputs: LanInputs?,
    ) {
        if (!enabled) {
            lanServer?.shutdown()
            lanServer = null
            sessions.removeForTransport(McpTransport.LAN)
            return
        }
        if (lanServer != null) return
        val activeInputs = inputs ?: return
        val lanAddress = localLanAddress() ?: throw IllegalStateException("No active LAN IPv4 address")
        lanServer = McpHttpServer(
            port = port,
            transport = McpTransport.LAN,
            credentialStore = McpClientCredentialStore(applicationContext),
            sessions = sessions,
            allowedLanHosts = McpLanHostAllowlist(applicationContext)::hosts,
            confirmationStore = confirmations,
            auditSink = RoomMcpAuditSink(activeInputs.database.mcpAuditEventDao()),
            imageCapabilities = activeInputs.capabilities,
            imageResolver = McpHistoryImageContentResolver(applicationContext, activeInputs.database.historyDao()),
            toolGateway = activeInputs.gateway,
            sseEvents = activeInputs.sseEvents,
            bindAddress = lanAddress,
        ).also(McpHttpServer::start)
    }

    private fun localLanAddress(): String? = NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        ?.hostAddress

    private data class LanInputs(
        val gateway: McpGenerationGateway,
        val capabilities: McpImageCapabilityStore,
        val database: AppDatabase,
        val scheduler: AndroidMcpGenerationScheduler,
        val sseEvents: McpSseEventStore,
    )

    private fun notification(): Notification {
        val appIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = PendingIntent.getActivity(this, 0, appIntent, PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, McpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.mcp_notify_title))
            .setContentText(getString(R.string.mcp_notify, McpHttpServer.DEFAULT_PORT))
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.mcp_stop), stopIntent)
            .setOngoing(true)
            .build()
    }

    data class Status(
        val running: Boolean = false,
        val ready: Boolean = false,
        val port: Int = McpHttpServer.DEFAULT_PORT,
        val transport: McpTransport = McpTransport.LOOPBACK,
        val error: String? = null,
    )

    companion object {
        const val ACTION_STOP = "io.github.xororz.localdream.STOP_MCP"
        const val EXTRA_PORT = "mcp_port"
        const val EXTRA_LAN_ENABLED = "mcp_lan_enabled"
        private const val CHANNEL_ID = "mcp_service"
        private const val NOTIFICATION_ID = 6
        private const val DISPATCH_OWNER = "mcp"
        private val _status = MutableStateFlow(Status())
        val status: StateFlow<Status> = _status
        private val confirmations = McpConfirmationStore()
        val confirmationRequests: StateFlow<List<McpPendingConfirmation>> = confirmations.uiRequests

        fun start(context: Context, port: Int = McpHttpServer.DEFAULT_PORT, lanEnabled: Boolean = false) {
            context.startForegroundService(
                Intent(context, McpService::class.java)
                    .putExtra(EXTRA_PORT, port)
                    .putExtra(EXTRA_LAN_ENABLED, lanEnabled),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, McpService::class.java).setAction(ACTION_STOP))
        }

        /** UI-local approval only; caller must render the action and target before invoking this. */
        fun approveConfirmation(requestId: String): String? = confirmations.approveUiRequest(requestId)

        fun rejectConfirmation(requestId: String) {
            confirmations.rejectUiRequest(requestId)
        }

        private fun updateStatus(status: Status) {
            _status.value = status
        }
    }
}
