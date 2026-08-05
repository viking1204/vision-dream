package io.github.xororz.localdream.ui.screens

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import io.github.xororz.localdream.data.AssetOrigin
import io.github.xororz.localdream.data.GenerationPreferences
import io.github.xororz.localdream.data.GenerationTask
import io.github.xororz.localdream.data.GenerationTaskStatus
import io.github.xororz.localdream.data.HistoryManager
import io.github.xororz.localdream.openai.BackendRuntimeCoordinator
import io.github.xororz.localdream.openai.ImageRequestParameters
import io.github.xororz.localdream.openai.InferenceArbiter
import io.github.xororz.localdream.openai.NativeBackendClient
import io.github.xororz.localdream.service.NativeRuntimeAttestationRecorder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Decides whether the queue may start another run right now.
 *
 * Extracted from the session so the drain rule stays unit-testable without a
 * backend: exactly one request starts per idle edge, and a queue restored from
 * a previous process stays parked until the user consents to draining it.
 */
internal fun shouldStartNextGeneration(
    queueAutoRun: Boolean,
    isGenerating: Boolean,
    pendingCount: Int,
): Boolean = queueAutoRun && !isGenerating && pendingCount > 0

/**
 * Process-scoped owner of the creation screen's generation lifecycle.
 *
 * Compose disposes a screen the moment the user taps another bottom-navigation
 * tab, which tears down `rememberCoroutineScope` and every `remember { ... }`
 * with it. A generation must not die that way: a run already streaming on the
 * NPU has to finish while the user browses the gallery, and the queue feeding
 * it has to survive the same trip. Previously the screen even cancelled the
 * backend socket in `onDispose`, so leaving the tab aborted the run mid-flight
 * and left the native backend in a state where the next queued task reported a
 * generation failure.
 *
 * Hoisting the run state here makes every `ChatGenerationScreen` composition a
 * pure view over one long-lived session. The process itself stays alive because
 * `BackendService` is already a foreground service for as long as a model is
 * loaded, so no extra keep-alive notification is needed.
 */
internal object ChatGenerationSession {

    /**
     * The parts of a run that only a composition can supply.
     *
     * Installed on every entry to the screen and deliberately never cleared:
     * the drain loop keeps running after the screen is gone and still needs
     * somewhere to save results.
     */
    internal class Environment(
        val context: Context,
        val coordinator: BackendRuntimeCoordinator,
        val historyManager: HistoryManager,
        val generationPreferences: GenerationPreferences,
        val busyMessage: String,
        val genericError: String,
    )

    /**
     * Outlives any composition. `Main.immediate` keeps the snapshot writes on
     * the same thread Compose reads them from, so a result appended while the
     * screen is away is already applied when the user navigates back.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Shared so a queued run reuses the socket the previous run warmed up. */
    val backendClient = NativeBackendClient()

    val messages = mutableStateListOf<ChatGenerationMessage>()
    val pendingQueue = mutableStateListOf<PendingChatRequest>()

    val isGeneratingState = mutableStateOf(false)
    val runningTaskState = mutableStateOf<GenerationTask?>(null)

    /** A restored queue must not start on its own; see [shouldStartNextGeneration]. */
    val queueAutoRunState = mutableStateOf(true)
    val queueRestoredState = mutableStateOf(false)
    val historyRestoredState = mutableStateOf(false)
    val nextMessageIdState = mutableLongStateOf(0L)

    private var activeJob: Job? = null

    @Volatile
    private var environment: Environment? = null

    fun installEnvironment(environment: Environment) {
        this.environment = environment
    }

    fun nextId(): Long {
        nextMessageIdState.longValue += 1L
        return nextMessageIdState.longValue
    }

    /** Projection persisted by the queue panel: the running task first. */
    fun queueTasks(): List<GenerationTask> =
        listOfNotNull(runningTaskState.value) + pendingQueue.map { it.task }

    /**
     * Replays a conversation stored by a previous process. Guarded because the
     * transcript now lives in this session: re-entering the screen must not
     * append the same history a second time.
     */
    fun restoreHistory(restored: List<ChatGenerationMessage>) {
        if (historyRestoredState.value) return
        historyRestoredState.value = true
        if (restored.isEmpty()) return
        messages.addAll(restored)
        nextMessageIdState.longValue = restored.maxOf { it.id } + 1L
    }

    /**
     * Adds work submitted from the composer. An explicit submit is also the
     * consent to drain anything parked from a previous session.
     */
    fun enqueue(requests: List<PendingChatRequest>) {
        queueAutoRunState.value = true
        pendingQueue.addAll(requests)
        drainNext()
    }

    /** Adds a queue revived from storage without starting it behind the user's back. */
    fun adoptRestoredQueue(revived: List<PendingChatRequest>) {
        if (revived.isEmpty()) return
        if (!isGeneratingState.value && pendingQueue.isEmpty()) {
            queueAutoRunState.value = false
        }
        pendingQueue.addAll(revived)
    }

    /** The queue panel's explicit "start" action. */
    fun startQueue() {
        queueAutoRunState.value = true
        drainNext()
    }

    /**
     * Starts the next request if the queue is allowed to advance.
     *
     * Called both from the UI (a visible screen enqueuing work) and from the
     * tail of a finished run (so the queue keeps draining while the screen is
     * disposed). Re-entrant calls are harmless: [startGeneration] flips
     * `isGenerating` synchronously on the main thread before it returns.
     */
    fun drainNext() {
        if (!shouldStartNextGeneration(
                queueAutoRun = queueAutoRunState.value,
                isGenerating = isGeneratingState.value,
                pendingCount = pendingQueue.size,
            )
        ) {
            return
        }
        startGeneration(pendingQueue.removeAt(0))
    }

    /** Aborts the in-flight run; the queue keeps whatever has not started yet. */
    fun cancelActiveGeneration() {
        backendClient.cancelAll()
        activeJob?.cancel()
        activeJob = null
    }

    fun startGeneration(request: PendingChatRequest) {
        val env = environment ?: return
        if (!InferenceArbiter.process.tryAcquireForApp()) {
            messages += ChatGenerationMessage.Error(nextId(), env.busyMessage)
            return
        }
        val entry = request.entry
        val settings = request.settings
        val submittedPrompt = request.prompt
        val submittedNegativePrompt = request.negativePrompt
        val sourceImage = request.sourceImage
        val requestMode = request.chatMode
        isGeneratingState.value = true
        runningTaskState.value = request.task.copy(status = GenerationTaskStatus.RUNNING)
        activeJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val dimensions = env.coordinator.ensureReady(
                    entry = entry,
                    requestedWidth = settings.width,
                    requestedHeight = settings.height,
                )
                val image = withContext(Dispatchers.IO) {
                    completeChatNativeGeneration(
                        generate = {
                            backendClient.generate(
                                parameters = ImageRequestParameters(
                                    modelId = entry.id,
                                    prompt = submittedPrompt,
                                    negativePrompt = submittedNegativePrompt,
                                    width = dimensions.first,
                                    height = dimensions.second,
                                    steps = settings.steps,
                                    cfg = settings.cfg,
                                    seed = settings.seed,
                                    scheduler = settings.scheduler,
                                    sourceImage = sourceImage,
                                ),
                                width = dimensions.first,
                                height = dimensions.second,
                            )
                        },
                        // A returned native image is the only success boundary. Exceptions
                        // and coroutine cancellation escape before this callback is reached.
                        onNativeGenerationSuccess = {
                            NativeRuntimeAttestationRecorder.record(env.context, entry.id)
                        },
                    )
                }
                val generationTime = (
                    (SystemClock.elapsedRealtime() - startedAt) / 1000f
                    ).let { "%.1fs".format(it) }
                val saved = env.historyManager.enqueueEncodedImageSave(
                    modelId = entry.id,
                    encodedImage = image.bytes,
                    mimeType = image.mimeType,
                    params = GenerationParameters(
                        steps = settings.steps,
                        cfg = settings.cfg,
                        seed = image.seed ?: settings.seed,
                        prompt = submittedPrompt,
                        negativePrompt = submittedNegativePrompt,
                        generationTime = generationTime,
                        width = dimensions.first,
                        height = dimensions.second,
                        runOnCpu = entry.model?.runOnCpu == true,
                        scheduler = settings.scheduler,
                        mode = requestMode.toGenerationMode(),
                    ),
                    mode = requestMode.toGenerationMode(),
                    origin = AssetOrigin.CHAT_GENERATION,
                ).await()
                messages += ChatGenerationMessage.Image(
                    id = nextId(),
                    file = saved?.imageFile,
                    fallbackBytes = image.bytes.takeIf { saved == null },
                    modelName = entry.name,
                    width = dimensions.first,
                    height = dimensions.second,
                    seed = image.seed ?: settings.seed,
                    prompt = submittedPrompt,
                    negativePrompt = submittedNegativePrompt,
                    steps = settings.steps,
                    cfg = settings.cfg,
                    scheduler = settings.scheduler,
                    generationTime = generationTime,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                messages += ChatGenerationMessage.Error(
                    id = nextId(),
                    message = error.message ?: env.genericError,
                )
            } finally {
                InferenceArbiter.process.releaseFromApp()
                isGeneratingState.value = false
                runningTaskState.value = null
                activeJob = null
                // The screen's own persistence effects only run while it is
                // composed, so a run that finished in the background has to
                // write its own result through.
                withContext(NonCancellable) { persist(env) }
                drainNext()
            }
        }
    }

    private suspend fun persist(env: Environment) {
        runCatching {
            if (messages.isNotEmpty()) {
                env.generationPreferences.saveChatHistoryJson(messages.toChatHistoryJson())
            }
            if (queueRestoredState.value) {
                env.generationPreferences.saveGenerationQueue(queueTasks())
            }
        }
    }
}
