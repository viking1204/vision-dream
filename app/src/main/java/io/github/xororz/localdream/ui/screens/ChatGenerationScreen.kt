package io.github.xororz.localdream.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhotoFilter
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.CreationDraft
import io.github.xororz.localdream.data.GenerationDefaults
import io.github.xororz.localdream.data.GenerationMode
import io.github.xororz.localdream.data.GenerationPreferences
import io.github.xororz.localdream.data.GenerationQueueSorter
import io.github.xororz.localdream.data.GenerationTask
import io.github.xororz.localdream.data.GenerationTaskStatus
import io.github.xororz.localdream.data.HistoryManager
import io.github.xororz.localdream.data.ModelTagDerivation
import io.github.xororz.localdream.navigation.Screen
import io.github.xororz.localdream.navigation.popBackStackIfResumed
import io.github.xororz.localdream.openai.BackendRuntimeCoordinator
import io.github.xororz.localdream.openai.InstalledModelCatalog
import io.github.xororz.localdream.service.BackendService
import io.github.xororz.localdream.ui.components.GenerationQueueBar
import io.github.xororz.localdream.ui.components.GenerationQueueSheet
import io.github.xororz.localdream.ui.components.PromptPickerDialog
import io.github.xororz.localdream.ui.components.RevealableImage
import io.github.xororz.localdream.ui.components.ZoomableImageOverlay
import io.github.xororz.localdream.utils.ParamShare
import io.github.xororz.localdream.utils.schedulerDisplayName
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class ChatGenerationSettings(
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfg: Float,
    val seed: Long?,
    val scheduler: String,
)

/**
 * Immutable snapshot of everything one generation run needs.
 *
 * Taking the snapshot at submit time is what lets the composer stay editable
 * while a run is in flight: later edits to the prompt, mode or advanced
 * settings cannot retro-actively mutate an already queued request.
 */
internal class PendingChatRequest(
    val entry: InstalledModelCatalog.Entry,
    val prompt: String,
    val negativePrompt: String,
    val settings: ChatGenerationSettings,
    val chatMode: ChatMode,
    val sourceImage: ByteArray?,
    /** Persistable projection rendered by the queue panel. */
    val task: GenerationTask,
)

/** Rebuilds an in-memory request from a queue entry restored out of DataStore. */
internal fun PendingChatRequest(
    entry: InstalledModelCatalog.Entry,
    task: GenerationTask,
): PendingChatRequest = PendingChatRequest(
    entry = entry,
    prompt = task.prompt,
    negativePrompt = task.negativePrompt,
    settings = ChatGenerationSettings(
        width = task.width,
        height = task.height,
        steps = task.steps,
        cfg = task.cfg,
        seed = task.seed,
        scheduler = task.scheduler,
    ),
    chatMode = ChatMode.fromKey(task.mode),
    // Source bytes are never persisted, so only TXT2IMG entries survive a restart.
    sourceImage = null,
    task = task.copy(status = GenerationTaskStatus.QUEUED),
)

/** Generation modes exposed by the chat composer. Keeps prompt and model
 *  context when switched; image-based modes require a source image. */
internal enum class ChatMode(val key: String) {
    TXT2IMG("TXT2IMG"),
    IMG2IMG("IMG2IMG"),
    INPAINT("INPAINT"),
    UPSCALE("UPSCALE"),
    ;

    val needsSourceImage: Boolean get() = this != TXT2IMG

    /** Glyph shown in the icon-only composer bar. */
    fun icon(): ImageVector = when (this) {
        TXT2IMG -> Icons.Default.TextFields
        IMG2IMG -> Icons.Default.PhotoFilter
        INPAINT -> Icons.Default.Brush
        UPSCALE -> Icons.Default.ZoomIn
    }

    /** Label used by the mode popover and the composer caption. */
    fun labelRes(): Int = when (this) {
        TXT2IMG -> R.string.chat_generation_mode_txt2img
        IMG2IMG -> R.string.chat_generation_mode_img2img
        INPAINT -> R.string.chat_generation_mode_inpaint
        UPSCALE -> R.string.chat_generation_mode_upscale
    }

    fun toGenerationMode(): GenerationMode = when (this) {
        TXT2IMG -> GenerationMode.TXT2IMG
        IMG2IMG -> GenerationMode.IMG2IMG
        INPAINT -> GenerationMode.INPAINT
        UPSCALE -> GenerationMode.IMG2IMG
    }

    companion object {
        fun fromKey(key: String): ChatMode = entries.firstOrNull { it.key == key } ?: TXT2IMG
    }
}

/** Cap of messages rendered until the user expands the history window. */
private const val MAX_VISIBLE_MESSAGES = 10

/** Runs the success-only side effect after a Chat native image is returned. */
internal fun <T> completeChatNativeGeneration(
    generate: () -> T,
    onNativeGenerationSuccess: () -> Unit,
): T {
    val generated = generate()
    onNativeGenerationSuccess()
    return generated
}

/**
 * A focused conversation-style entry point for local text-to-image generation.
 *
 * The screen acquires the process-wide inference lease before it loads or uses
 * a model. This makes a queued API request and an interactive chat request
 * mutually exclusive while preserving the app's single loaded-model invariant.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatGenerationScreen(
    navController: NavController,
    isTopLevel: Boolean = false,
    bottomBar: @Composable () -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val catalog = remember { InstalledModelCatalog(context) }
    val coordinator = remember { BackendRuntimeCoordinator(context) }
    val historyManager = remember { HistoryManager(context) }
    val generationPreferences = remember { GenerationPreferences(context) }
    // The run lifecycle lives outside the composition so leaving this tab no
    // longer aborts an in-flight generation; see ChatGenerationSession.
    val session = ChatGenerationSession
    val messages = session.messages
    val listState = rememberLazyListState()
    // G8: multi-select mode for deleting conversation messages. Deleting only
    // removes entries from this in-memory list + the stored chat JSON; asset
    // files on disk are never touched.
    var selectionMode by remember { mutableStateOf(false) }
    val selectedMessageIds = remember { mutableStateListOf<Long>() }

    var installedModels by remember {
        mutableStateOf<List<InstalledModelCatalog.Entry>>(emptyList())
    }
    var modelsLoading by remember { mutableStateOf(true) }
    var modelLoadError by remember { mutableStateOf<String?>(null) }
    var selectedModelIdsCsv by rememberSaveable { mutableStateOf("") }
    val selectedModelIds = if (selectedModelIdsCsv.isBlank()) {
        emptySet<String>()
    } else {
        selectedModelIdsCsv.split(',').toSet()
    }
    var prompt by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    var negativePrompt by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(GenerationDefaults.GLOBAL.negativePrompt))
    }
    val globalNegativePrompt by generationPreferences.observeGlobalNegativePrompt()
        .collectAsState(initial = GenerationDefaults.DEFAULT_NEGATIVE_PROMPT)
    var widthText by rememberSaveable { mutableStateOf("512") }
    var heightText by rememberSaveable { mutableStateOf("512") }
    var stepsText by rememberSaveable { mutableStateOf("20") }
    var cfgText by rememberSaveable { mutableStateOf("7") }
    var seedText by rememberSaveable { mutableStateOf("") }
    var scheduler by rememberSaveable { mutableStateOf("dpm") }
    var showModelPicker by remember { mutableStateOf(false) }
    var showAdvancedSettings by remember { mutableStateOf(false) }
    var showPromptPicker by remember { mutableStateOf(false) }
    val isGenerating by session.isGeneratingState
    val pendingQueue = session.pendingQueue
    val runningTask by session.runningTaskState
    var showQueuePanel by remember { mutableStateOf(false) }
    var queueRestored by session.queueRestoredState
    // A queue restored from a previous process must not start on its own: the
    // user opens the app, they do not expect the GPU to be busy immediately.
    val queueAutoRun by session.queueAutoRunState
    val smartSortEnabled by generationPreferences.observeQueueSmartSort()
        .collectAsState(initial = false)

    var chatMode by rememberSaveable { mutableStateOf(ChatMode.TXT2IMG) }
    var sourceImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var visibleMessageCount by remember { mutableStateOf(MAX_VISIBLE_MESSAGES) }

    val sourceImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        sourceImageBytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
    }

    // `remember(messages, ...)` used to key on the SnapshotStateList *identity*,
    // which never changes on add/remove because the session owns one long-lived
    // list. Once the conversation grew past MAX_VISIBLE_MESSAGES the windowed
    // view froze on its first takeLast(N) snapshot, so new results and deletions
    // only surfaced after leaving and re-entering the screen. derivedStateOf
    // tracks the snapshot reads performed inside the block instead, so the
    // window invalidates on append, delete and any in-place element swap.
    val visibleMessages by remember(visibleMessageCount) {
        derivedStateOf {
            if (messages.size <= visibleMessageCount) {
                messages.toList()
            } else {
                messages.takeLast(visibleMessageCount)
            }
        }
    }

    LaunchedEffect(globalNegativePrompt) {
        if (negativePrompt.text.isBlank() ||
            negativePrompt.text == GenerationDefaults.DEFAULT_NEGATIVE_PROMPT
        ) {
            negativePrompt = TextFieldValue(
                globalNegativePrompt,
                TextRange(globalNegativePrompt.length),
            )
        }
    }

    val selectedModels = installedModels.filter { it.id in selectedModelIds }
    val selectedModelName: String? = when {
        selectedModels.isEmpty() -> null
        selectedModels.size == 1 -> selectedModels.first().name
        else -> stringResource(R.string.chat_generation_models_selected, selectedModels.size)
    }
    val selectedModelBackend: String? = selectedModels.firstOrNull()
        ?.model?.runOnCpu?.let { if (it) "CPU" else "NPU" }
    val busyMessage = stringResource(R.string.chat_generation_busy)
    val genericError = stringResource(R.string.chat_generation_error)
    val invalidSettings = stringResource(R.string.chat_generation_invalid_settings)
    val sourceImageRequired = stringResource(R.string.chat_generation_source_image_required)
    val selectModelRequired = stringResource(R.string.chat_generation_select_at_least_one_model)

    fun nextId(): Long = session.nextId()

    // Re-installed on every entry so the long-lived runner always holds the
    // current locale's strings, and never cleared: a run that outlives this
    // composition still needs somewhere to write its result.
    SideEffect {
        session.installEnvironment(
            ChatGenerationSession.Environment(
                context = context,
                coordinator = coordinator,
                historyManager = historyManager,
                generationPreferences = generationPreferences,
                busyMessage = busyMessage,
                genericError = genericError,
            ),
        )
    }

    fun applyModelDefaults(entry: InstalledModelCatalog.Entry) {
        val defaultSize = entry.model?.let { model ->
            defaultGenerationSize(model.usesFixedCanvas, model.runOnCpu)
        } ?: entry.generationSize
        widthText = defaultSize.toString()
        heightText = defaultSize.toString()
        entry.model?.defaults?.let { defaults ->
            stepsText = defaults.steps.roundToInt().toString()
            cfgText = defaults.cfg.toString()
            scheduler = defaults.scheduler
        }
    }

    LaunchedEffect(Unit) {
        modelsLoading = true
        modelLoadError = null
        runCatching {
            catalog.all().filter { it.kind == InstalledModelCatalog.Kind.GENERATION }
        }.onSuccess { entries ->
            installedModels = entries
            val preferred = entries.firstOrNull { it.id in selectedModelIds }
                ?: entries.firstOrNull { it.id == BackendService.servingModelId.value }
                ?: entries.firstOrNull()
            if (preferred != null && preferred.id !in selectedModelIds) {
                selectedModelIdsCsv = preferred.id
                applyModelDefaults(preferred)
            }
        }.onFailure { error ->
            modelLoadError = error.message ?: genericError
        }
        modelsLoading = false
    }

    LaunchedEffect(Unit) {
        generationPreferences.getCreationDraft()?.let { draft ->
            if (prompt.text.isBlank()) {
                prompt = TextFieldValue(draft.prompt, TextRange(draft.prompt.length))
            }
            if (negativePrompt.text.isBlank()) {
                negativePrompt = TextFieldValue(
                    draft.negativePrompt,
                    TextRange(draft.negativePrompt.length),
                )
            }
            chatMode = ChatMode.fromKey(draft.mode)
            widthText = draft.width.toString()
            heightText = draft.height.toString()
            stepsText = draft.steps.toString()
            cfgText = draft.cfg.toString()
            seedText = draft.seed
            scheduler = draft.scheduler
            draft.modelId?.let { id -> selectedModelIdsCsv = id }
        }
    }

    // 恢复上一次的创作对话，让创作历史在进程被杀/App 重启后仍能保留。
    // 图片复用统一资产管理器已落盘的文件；文件已不存在的图会被丢弃。
    LaunchedEffect(Unit) {
        if (session.historyRestoredState.value) return@LaunchedEffect
        val restored = generationPreferences.getChatHistoryJson()
            ?.let { chatHistoryFromJson(it) }
            .orEmpty()
        // The transcript now outlives the composition, so replaying it on every
        // visit would duplicate the whole conversation.
        session.restoreHistory(restored)
    }

    LaunchedEffect(
        prompt.text,
        negativePrompt.text,
        selectedModelIds,
        chatMode,
        widthText,
        heightText,
        stepsText,
        cfgText,
        seedText,
        scheduler,
    ) {
        delay(400)
        generationPreferences.saveCreationDraft(
            CreationDraft(
                prompt = prompt.text,
                negativePrompt = negativePrompt.text,
                modelId = selectedModelIds.firstOrNull(),
                mode = chatMode.key,
                width = widthText.toIntOrNull() ?: 512,
                height = heightText.toIntOrNull() ?: 512,
                steps = stepsText.toIntOrNull() ?: 20,
                cfg = cfgText.toFloatOrNull() ?: 7f,
                seed = seedText,
                scheduler = scheduler,
            ),
        )
    }

    // Persist the creation conversation whenever a message is added (the list
    // only grows in this screen, so size is a reliable change signal). Debounced
    // so rapid appends coalesce into a single write.
    LaunchedEffect(messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        delay(400)
        generationPreferences.saveChatHistoryJson(messages.toChatHistoryJson())
    }

    // G7: when (re)entering the screen, jump instantly to the latest message
    // instead of animating, so restored history is already scrolled to the
    // bottom. Subsequent appends during a session animate so new content is
    // visibly revealed.
    var initialScrollDone by remember { mutableStateOf(false) }
    LaunchedEffect(messages.size, isGenerating, visibleMessageCount) {
        // An empty list must NOT consume the "initial" budget: restoring the
        // persisted conversation happens a frame or two after composition, so
        // flipping the flag here would demote the first real positioning to an
        // animated scroll and make entering the screen crawl through history.
        if (visibleMessages.isEmpty()) return@LaunchedEffect
        val lastIndex = visibleMessages.size + if (isGenerating) 0 else -1
        if (lastIndex < 0) return@LaunchedEffect
        if (!initialScrollDone) {
            listState.scrollToItem(lastIndex)
            initialScrollDone = true
        } else {
            listState.animateScrollToItem(lastIndex)
        }
    }

    val submitGeneration: () -> Unit = {
        val selectedEntries = installedModels.filter { it.id in selectedModelIds }
        val settings = parseChatGenerationSettings(
            width = widthText,
            height = heightText,
            steps = stepsText,
            cfg = cfgText,
            seed = seedText,
            scheduler = scheduler,
        )
        val submittedPrompt = prompt.text.trim()
        when {
            selectedEntries.isEmpty() -> {
                messages += ChatGenerationMessage.Error(nextId(), selectModelRequired)
            }

            submittedPrompt.isEmpty() -> Unit

            settings == null -> {
                messages += ChatGenerationMessage.Error(nextId(), invalidSettings)
            }

            chatMode.needsSourceImage && sourceImageBytes == null -> {
                messages += ChatGenerationMessage.Error(nextId(), sourceImageRequired)
            }

            else -> {
                val resolvedNegativePrompt = negativePrompt.text.trim()
                    .ifBlank { globalNegativePrompt }
                // One prompt splits into N per-model tasks; each becomes its own
                // generation and appends its own image to the conversation.
                val requests = selectedEntries.map { entry ->
                    PendingChatRequest(
                        entry = entry,
                        prompt = submittedPrompt,
                        negativePrompt = resolvedNegativePrompt,
                        settings = settings,
                        chatMode = chatMode,
                        sourceImage = sourceImageBytes,
                        task = GenerationTask(
                            id = UUID.randomUUID().toString(),
                            modelId = entry.id,
                            modelName = entry.name,
                            prompt = submittedPrompt,
                            negativePrompt = resolvedNegativePrompt,
                            mode = chatMode.key,
                            width = settings.width,
                            height = settings.height,
                            steps = settings.steps,
                            cfg = settings.cfg,
                            seed = settings.seed,
                            scheduler = settings.scheduler,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
                // The prompt is intentionally NOT echoed into the conversation
                // here. Queued work is represented by the queue bar/panel; the
                // prompt reaches the transcript together with its image once
                // the task actually runs, so one generation = one message.
                prompt = TextFieldValue()
                keyboardController?.hide()
                // The composer stays editable during a run, so extra submissions
                // queue up instead of being rejected by the inference arbiter.
                // All per-model tasks drain sequentially through the arbiter,
                // and an explicit submit is also the consent to drain anything
                // restored from a previous session.
                session.enqueue(requests)
            }
        }
    }

    // Restores the queue parked by a previous process. Runs once the catalog is
    // known so every entry can be matched back to an installed model; orphaned
    // tasks (model uninstalled) are dropped rather than replayed blindly.
    LaunchedEffect(modelsLoading) {
        if (queueRestored || modelsLoading) return@LaunchedEffect
        val restored = generationPreferences.getGenerationQueue()
        val revived = restored.mapNotNull { task ->
            installedModels.firstOrNull { it.id == task.modelId }
                ?.let { entry -> PendingChatRequest(entry = entry, task = task) }
        }
        // Parks the revived queue unless the user has already started working:
        // an in-session submit is explicit consent to keep draining.
        session.adoptRestoredQueue(revived)
        queueRestored = true
    }

    // Smart sort clusters same-model runs so the native backend reloads weights
    // once per model instead of once per task. Size is a stable relaunch key:
    // reordering keeps it constant, so this cannot loop.
    LaunchedEffect(smartSortEnabled, pendingQueue.size) {
        if (!smartSortEnabled) return@LaunchedEffect
        val current = pendingQueue.toList()
        val clustered = GenerationQueueSorter.clusterByModel(current.map { it.task })
        if (clustered.map { it.id } != current.map { it.task.id }) {
            val byId = current.associateBy { it.task.id }
            pendingQueue.clear()
            pendingQueue.addAll(clustered.mapNotNull { byId[it.id] })
        }
    }

    val queueTasks = session.queueTasks()

    // Persist on any membership or status change. Skipped until the restore has
    // run, otherwise the first empty composition would wipe the stored queue.
    val queueSignature = queueTasks.joinToString("|") { "${it.id}:${it.status.name}" }
    LaunchedEffect(queueSignature, queueRestored) {
        if (!queueRestored) return@LaunchedEffect
        generationPreferences.saveGenerationQueue(queueTasks)
    }

    // Drains the queue on the idle edge: exactly one request starts per
    // completed run, so the native backend never sees concurrent sessions.
    // This only covers edges observed while the screen is composed; a run that
    // finishes in the background chains the next one from the session itself.
    LaunchedEffect(isGenerating, pendingQueue.size, queueAutoRun) {
        session.drainNext()
    }

    val allMessagesSelected = messages.isNotEmpty() &&
        selectedMessageIds.size == messages.size

    Scaffold(
        topBar = {
            // G9: as a top-level destination the screen is already labelled by
            // the bottom navigation, so the app bar is dropped entirely and the
            // reclaimed height goes to the transcript. It only comes back when
            // it carries an action: nested navigation (back) or multi-select.
            if (!isTopLevel || selectionMode) {
                CenterAlignedTopAppBar(
                    title = {
                        if (selectionMode) {
                            Text(
                                text = stringResource(
                                    R.string.selected_count,
                                    selectedMessageIds.size,
                                ),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.chat_generation_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    },
                    actions = {
                        if (selectionMode) {
                            IconButton(onClick = {
                                // Selection spans the whole conversation, not
                                // just the paged-in window.
                                selectedMessageIds.clear()
                                if (!allMessagesSelected) {
                                    selectedMessageIds.addAll(messages.map { it.id })
                                }
                            }) {
                                Icon(
                                    imageVector = if (allMessagesSelected) {
                                        Icons.Default.Deselect
                                    } else {
                                        Icons.Default.SelectAll
                                    },
                                    contentDescription = stringResource(
                                        if (allMessagesSelected) {
                                            R.string.deselect_all
                                        } else {
                                            R.string.select_all
                                        },
                                    ),
                                )
                            }
                            IconButton(
                                onClick = {
                                    // G8: only remove conversation entries; asset
                                    // files referenced by Image messages stay on disk.
                                    messages.removeAll { it.id in selectedMessageIds }
                                    selectedMessageIds.clear()
                                    selectionMode = false
                                    scope.launch {
                                        generationPreferences.saveChatHistoryJson(
                                            messages.toChatHistoryJson(),
                                        )
                                    }
                                },
                                enabled = selectedMessageIds.isNotEmpty(),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.delete_messages),
                                )
                            }
                            IconButton(onClick = {
                                selectedMessageIds.clear()
                                selectionMode = false
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.cancel),
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        if (!isTopLevel && !selectionMode) {
                            IconButton(onClick = { navController.popBackStackIfResumed() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back),
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    ),
                )
            }
        },
        bottomBar = bottomBar,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 10.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (messages.isEmpty() && !isGenerating) {
                    item(key = "empty") {
                        Box(
                            modifier = Modifier
                                .fillParentMaxHeight()
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            ChatGenerationEmptyState(
                                modelsLoading = modelsLoading,
                                modelLoadError = modelLoadError,
                                hasModels = installedModels.isNotEmpty(),
                                selectedModelName = selectedModelName,
                            )
                        }
                    }
                }
                if (messages.size > visibleMessageCount) {
                    item(key = "load_earlier") {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            TextButton(onClick = {
                                visibleMessageCount += MAX_VISIBLE_MESSAGES
                            }) {
                                Text(stringResource(R.string.chat_generation_load_earlier))
                            }
                        }
                    }
                }
                items(
                    items = visibleMessages,
                    key = { message -> "message_${message.id}" },
                    contentType = { message -> message::class },
                ) { message ->
                    ChatGenerationMessageItem(
                        message = message,
                        isSelected = message.id in selectedMessageIds,
                        onDelete = {
                            // Same contract as batch delete: drop the transcript
                            // entry, never the asset file on disk.
                            messages.removeAll { it.id == message.id }
                            selectedMessageIds.remove(message.id)
                            if (selectedMessageIds.isEmpty()) selectionMode = false
                            scope.launch {
                                generationPreferences.saveChatHistoryJson(
                                    messages.toChatHistoryJson(),
                                )
                            }
                        },
                        onLongClick = {
                            if (!selectionMode) {
                                selectionMode = true
                                selectedMessageIds.add(message.id)
                            } else {
                                if (message.id in selectedMessageIds) {
                                    selectedMessageIds.remove(message.id)
                                } else {
                                    selectedMessageIds.add(message.id)
                                }
                                if (selectedMessageIds.isEmpty()) selectionMode = false
                            }
                        },
                        onClick = {
                            if (selectionMode) {
                                if (message.id in selectedMessageIds) {
                                    selectedMessageIds.remove(message.id)
                                } else {
                                    selectedMessageIds.add(message.id)
                                }
                                if (selectedMessageIds.isEmpty()) selectionMode = false
                            }
                        },
                    )
                }
                if (isGenerating) {
                    item(key = "generating") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Row(
                                    modifier = Modifier.padding(
                                        horizontal = 16.dp,
                                        vertical = 12.dp,
                                    ),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                    Text(stringResource(R.string.chat_generation_generating))
                                }
                            }
                        }
                    }
                }
            }
            GenerationQueueBar(
                pendingCount = pendingQueue.size,
                runningModelName = runningTask?.modelName,
                onOpenPanel = { showQueuePanel = true },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            ChatGenerationComposer(
                selectedModelName = selectedModelName,
                selectedModelBackend = selectedModelBackend,
                prompt = prompt,
                negativePrompt = negativePrompt,
                isGenerating = isGenerating,
                pendingCount = pendingQueue.size,
                hasModels = installedModels.isNotEmpty(),
                selectedModelIds = selectedModelIds,
                chatMode = chatMode,
                onModeChange = { chatMode = it },
                sourceImageBytes = sourceImageBytes,
                onPickSourceImage = { sourceImagePicker.launch("image/*") },
                onClearSourceImage = { sourceImageBytes = null },
                onPromptChange = { candidate ->
                    val pasted = ParamShare.tryDecodePromptPairEdit(
                        currentText = prompt.text,
                        selectionStart = prompt.selection.start,
                        selectionEnd = prompt.selection.end,
                        candidate = candidate.text,
                    )
                    if (pasted == null) {
                        prompt = candidate
                    } else {
                        prompt = TextFieldValue(
                            pasted.prompt,
                            TextRange(pasted.prompt.length),
                        )
                        negativePrompt = TextFieldValue(
                            pasted.negativePrompt,
                            TextRange(pasted.negativePrompt.length),
                        )
                    }
                },
                onNegativePromptChange = { candidate ->
                    val pasted = ParamShare.tryDecodePromptPairEdit(
                        currentText = negativePrompt.text,
                        selectionStart = negativePrompt.selection.start,
                        selectionEnd = negativePrompt.selection.end,
                        candidate = candidate.text,
                    )
                    if (pasted == null) {
                        negativePrompt = candidate
                    } else {
                        prompt = TextFieldValue(
                            pasted.prompt,
                            TextRange(pasted.prompt.length),
                        )
                        negativePrompt = TextFieldValue(
                            pasted.negativePrompt,
                            TextRange(pasted.negativePrompt.length),
                        )
                    }
                },
                onModelClick = { showModelPicker = true },
                onPromptPickerClick = { showPromptPicker = true },
                onAdvancedSettingsClick = { showAdvancedSettings = true },
                onSend = submitGeneration,
            )
        }
    }

    if (showModelPicker) {
        ChatGenerationModelPicker(
            models = installedModels,
            selectedModelIds = selectedModelIds,
            onToggle = { entry ->
                val next = if (entry.id in selectedModelIds) {
                    selectedModelIds - entry.id
                } else {
                    selectedModelIds + entry.id
                }
                selectedModelIdsCsv = next.joinToString(",")
            },
            onConfirm = {
                if (selectedModelIds.size == 1) {
                    installedModels.firstOrNull { it.id in selectedModelIds }
                        ?.let { applyModelDefaults(it) }
                }
                showModelPicker = false
            },
            onClearAll = { selectedModelIdsCsv = "" },
            onDismiss = { showModelPicker = false },
        )
    }

    if (showQueuePanel) {
        GenerationQueueSheet(
            tasks = queueTasks,
            smartSortEnabled = smartSortEnabled,
            onSmartSortChange = { enabled ->
                scope.launch { generationPreferences.setQueueSmartSort(enabled) }
            },
            onRemove = { task ->
                pendingQueue.removeAll { it.task.id == task.id }
            },
            onMove = { task, delta ->
                val index = pendingQueue.indexOfFirst { it.task.id == task.id }
                val target = index + delta
                if (index >= 0 && target in pendingQueue.indices) {
                    pendingQueue.add(target, pendingQueue.removeAt(index))
                }
            },
            onClear = { pendingQueue.clear() },
            onDismiss = { showQueuePanel = false },
            onStartQueue = if (!queueAutoRun && pendingQueue.isNotEmpty()) {
                {
                    session.startQueue()
                    showQueuePanel = false
                }
            } else {
                null
            },
        )
    }

    if (showAdvancedSettings) {
        ChatGenerationAdvancedSettings(
            width = widthText,
            height = heightText,
            steps = stepsText,
            cfg = cfgText,
            seed = seedText,
            scheduler = scheduler,
            onWidthChange = { widthText = it },
            onHeightChange = { heightText = it },
            onStepsChange = { stepsText = it },
            onCfgChange = { cfgText = it },
            onSeedChange = { seedText = it },
            onSchedulerChange = { scheduler = it },
            onDismiss = { showAdvancedSettings = false },
        )
    }

    if (showPromptPicker) {
        PromptPickerDialog(
            onDismissRequest = { showPromptPicker = false },
            modelId = selectedModelIds.firstOrNull(),
            onNavigateToCreate = { navController.navigate(Screen.PromptManager.route) },
            onTemplateSelected = { template ->
                prompt = TextFieldValue(
                    template.prompt,
                    TextRange(template.prompt.length),
                )
                negativePrompt = TextFieldValue(
                    template.negativePrompt,
                    TextRange(template.negativePrompt.length),
                )
                template.sampling?.let { sampling ->
                    stepsText = sampling.steps.toString()
                    cfgText = sampling.cfg.toString()
                    scheduler = sampling.scheduler
                }
                showPromptPicker = false
            },
        )
    }
}

@Composable
private fun ChatGenerationEmptyState(
    modelsLoading: Boolean,
    modelLoadError: String?,
    hasModels: Boolean,
    selectedModelName: String?,
    modifier: Modifier = Modifier,
) {
    val message = when {
        modelsLoading -> stringResource(R.string.chat_generation_loading_models)

        modelLoadError != null -> stringResource(
            R.string.chat_generation_load_models_failed,
            modelLoadError,
        )

        !hasModels -> stringResource(R.string.chat_generation_no_models)

        else -> stringResource(R.string.chat_generation_empty)
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(176.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.padding(12.dp).size(24.dp),
                    )
                }
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                )
                selectedModelName?.let { modelName ->
                    Text(
                        text = modelName,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatGenerationMessageItem(
    message: ChatGenerationMessage,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    onLongClick: () -> Unit = {},
    onClick: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val selectionModifier = modifier
        .fillMaxWidth()
        .combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        )
        .then(
            if (isSelected) {
                Modifier.background(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(12.dp),
                ).padding(4.dp)
            } else {
                Modifier
            },
        )
    when (message) {
        is ChatGenerationMessage.User -> {
            Row(
                modifier = selectionModifier,
                horizontalArrangement = Arrangement.End,
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(0.82f),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Text(
                        text = message.prompt,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        is ChatGenerationMessage.Image -> {
            var showLightbox by remember { mutableStateOf(false) }
            var showDetails by remember { mutableStateOf(false) }
            val lightboxBitmap = remember(message.id) {
                message.file?.let { BitmapFactory.decodeFile(it.absolutePath) }
                    ?: message.fallbackBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
            Row(
                modifier = selectionModifier,
                horizontalArrangement = Arrangement.Start,
            ) {
                Card(modifier = Modifier.fillMaxWidth(0.94f)) {
                    RevealableImage(
                        revealKey = message.id,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(
                                message.width.toFloat() /
                                    message.height.coerceAtLeast(1).toFloat(),
                            ),
                        onOpenPreview = { showLightbox = true },
                    ) {
                        AsyncImage(
                            model = message.file ?: message.fallbackBytes,
                            contentDescription = stringResource(
                                R.string.chat_generation_generated_image,
                            ),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    // G9: the prompt lives on the image card instead of a
                    // separate bubble, so one generation reads as one message.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (message.prompt.isNotBlank()) {
                            Text(
                                text = message.prompt,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = listOf(
                                    message.modelName,
                                    message.generationTime,
                                ).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                                ChatImageAction(
                                    icon = Icons.Outlined.Info,
                                    descriptionRes = R.string.chat_generation_view_details,
                                    onClick = { showDetails = true },
                                )
                                ChatImageAction(
                                    icon = Icons.Outlined.OpenInFull,
                                    descriptionRes = R.string.chat_generation_view_large,
                                    onClick = { showLightbox = true },
                                )
                                ChatImageAction(
                                    icon = Icons.Outlined.Delete,
                                    descriptionRes = R.string.chat_generation_delete_message,
                                    onClick = onDelete,
                                )
                            }
                        }
                    }
                }
            }
            if (showLightbox) {
                ZoomableImageOverlay(
                    bitmap = lightboxBitmap,
                    onDismiss = { showLightbox = false },
                    zoomInEnabled = true,
                )
            }
            if (showDetails) {
                ImageDetailsSheet(
                    message = message,
                    onDismiss = { showDetails = false },
                )
            }
        }

        is ChatGenerationMessage.Error -> {
            Row(
                modifier = selectionModifier,
                horizontalArrangement = Arrangement.Start,
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(0.86f),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Text(
                        text = message.message,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/**
 * Compact, label-free action on an image card. The icon carries no text, so the
 * string resource is surfaced as the accessibility description instead.
 */
@Composable
private fun ChatImageAction(
    icon: ImageVector,
    @StringRes descriptionRes: Int,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(descriptionRes),
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageDetailsSheet(
    message: ChatGenerationMessage.Image,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = message.modelName.ifBlank { stringResource(R.string.chat_generation_select_model) },
                style = MaterialTheme.typography.titleMedium,
            )
            DetailRow(
                label = stringResource(R.string.chat_generation_prompt),
                value = message.prompt.ifBlank { "—" },
            )
            DetailRow(
                label = stringResource(R.string.chat_generation_negative_prompt),
                value = message.negativePrompt.ifBlank { "—" },
            )
            DetailRow(
                label = stringResource(R.string.chat_generation_steps),
                value = message.steps.toString(),
            )
            DetailRow(
                label = stringResource(R.string.chat_generation_cfg),
                value = message.cfg.toString(),
            )
            DetailRow(
                label = stringResource(
                    R.string.chat_generation_scheduler_value,
                    schedulerDisplayName(message.scheduler),
                ),
                value = "",
            )
            DetailRow(
                label = stringResource(R.string.chat_generation_seed),
                value = message.seed?.toString()
                    ?: stringResource(R.string.chat_generation_random_seed),
            )
            DetailRow(
                label = stringResource(R.string.chat_generation_width),
                value = "${message.width} × ${message.height}",
            )
            if (message.generationTime.isNotBlank()) {
                DetailRow(
                    label = stringResource(R.string.chat_generation_generation_time),
                    value = message.generationTime,
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    if (value.isEmpty()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ChatGenerationComposer(
    selectedModelName: String?,
    selectedModelBackend: String? = null,
    prompt: TextFieldValue,
    negativePrompt: TextFieldValue,
    isGenerating: Boolean,
    pendingCount: Int,
    hasModels: Boolean,
    selectedModelIds: Set<String>,
    chatMode: ChatMode,
    onModeChange: (ChatMode) -> Unit,
    sourceImageBytes: ByteArray?,
    onPickSourceImage: () -> Unit,
    onClearSourceImage: () -> Unit,
    onPromptChange: (TextFieldValue) -> Unit,
    onNegativePromptChange: (TextFieldValue) -> Unit,
    onModelClick: () -> Unit,
    onPromptPickerClick: () -> Unit,
    onAdvancedSettingsClick: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var negativePromptExpanded by rememberSaveable { mutableStateOf(false) }
    var showModeMenu by remember { mutableStateOf(false) }
    var showSourcePreview by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (sourceImageBytes != null) {
                val sourceBitmap = remember(sourceImageBytes) {
                    BitmapFactory.decodeByteArray(sourceImageBytes, 0, sourceImageBytes.size)
                }
                ComposerAttachmentChip(
                    bitmap = sourceBitmap,
                    onPreview = { showSourcePreview = true },
                    onClear = onClearSourceImage,
                )
            }
            // One rounded card holds the text and an icon-only action bar, the
            // way ChatGPT and WorkBuddy compose. Labels live in the popovers,
            // never in the bar; every icon still carries a contentDescription.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ComposerTextField(
                        value = prompt,
                        onValueChange = onPromptChange,
                        placeholder = stringResource(R.string.chat_generation_prompt_placeholder),
                        imeAction = ImeAction.Send,
                        onSend = onSend,
                        minLines = if (negativePromptExpanded) 2 else 3,
                        maxLines = 10,
                    )
                    if (negativePromptExpanded) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        ComposerTextField(
                            value = negativePrompt,
                            onValueChange = onNegativePromptChange,
                            placeholder = stringResource(
                                R.string.chat_generation_negative_prompt,
                            ),
                            imeAction = ImeAction.Default,
                            onSend = null,
                            minLines = 2,
                            maxLines = 4,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 2.dp, end = 6.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            ComposerIconButton(
                                icon = chatMode.icon(),
                                contentDescription = stringResource(chatMode.labelRes()),
                                onClick = { showModeMenu = true },
                                active = chatMode != ChatMode.TXT2IMG,
                            )
                            DropdownMenu(
                                expanded = showModeMenu,
                                onDismissRequest = { showModeMenu = false },
                            ) {
                                ChatMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(mode.labelRes())) },
                                        leadingIcon = {
                                            Icon(mode.icon(), contentDescription = null)
                                        },
                                        trailingIcon = {
                                            if (mode == chatMode) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                )
                                            }
                                        },
                                        onClick = {
                                            showModeMenu = false
                                            onModeChange(mode)
                                        },
                                    )
                                }
                            }
                        }
                        if (chatMode.needsSourceImage) {
                            ComposerIconButton(
                                icon = Icons.Default.Image,
                                contentDescription = stringResource(
                                    R.string.chat_generation_select_source_image,
                                ),
                                onClick = onPickSourceImage,
                                active = sourceImageBytes != null,
                            )
                        }
                        ComposerIconButton(
                            icon = Icons.Default.Memory,
                            contentDescription = selectedModelName
                                ?: stringResource(R.string.chat_generation_select_model),
                            onClick = onModelClick,
                            enabled = hasModels,
                            active = selectedModelName != null,
                        )
                        ComposerIconButton(
                            icon = Icons.Default.Bookmarks,
                            contentDescription = stringResource(
                                R.string.chat_generation_prompt_library,
                            ),
                            onClick = onPromptPickerClick,
                        )
                        ComposerIconButton(
                            icon = Icons.Default.Tune,
                            contentDescription = stringResource(
                                R.string.chat_generation_advanced,
                            ),
                            onClick = onAdvancedSettingsClick,
                        )
                        ComposerIconButton(
                            icon = Icons.Default.Block,
                            contentDescription = stringResource(
                                R.string.chat_generation_negative_prompt,
                            ),
                            onClick = { negativePromptExpanded = !negativePromptExpanded },
                            active = negativePromptExpanded || negativePrompt.text.isNotBlank(),
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        if (pendingCount > 0) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.chat_generation_queue_count,
                                        pendingCount,
                                    ),
                                    modifier = Modifier.padding(
                                        horizontal = 10.dp,
                                        vertical = 6.dp,
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                            Spacer(modifier = Modifier.size(8.dp))
                        }
                        FilledIconButton(
                            onClick = onSend,
                            enabled = prompt.text.isNotBlank() && selectedModelIds.isNotEmpty(),
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                imageVector = if (isGenerating) {
                                    Icons.AutoMirrored.Filled.PlaylistAdd
                                } else {
                                    Icons.AutoMirrored.Filled.Send
                                },
                                contentDescription = if (isGenerating) {
                                    stringResource(R.string.chat_generation_enqueue)
                                } else {
                                    stringResource(R.string.chat_generation_send)
                                },
                            )
                        }
                    }
                }
            }
            // Keeps the bar itself label-free while still telling the user
            // which model and mode the next run will use.
            Text(
                text = listOfNotNull(
                    selectedModelName ?: stringResource(R.string.chat_generation_select_model),
                    selectedModelBackend,
                    stringResource(chatMode.labelRes()),
                ).joinToString(" · "),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }

    if (showSourcePreview) {
        val previewBitmap = remember(sourceImageBytes) {
            sourceImageBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
        ZoomableImageOverlay(
            bitmap = previewBitmap,
            onDismiss = { showSourcePreview = false },
            zoomInEnabled = true,
        )
    }
}

/** Borderless multi-line field used inside the composer card. */
@Composable
private fun ComposerTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    imeAction: ImeAction,
    onSend: (() -> Unit)?,
    minLines: Int = 1,
    maxLines: Int = 5,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        minLines = minLines,
        maxLines = maxLines,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onSend = { onSend?.invoke() }),
    )
}

/**
 * Icon-only composer action. Stays on the 48dp minimum touch target while
 * rendering a 20dp glyph, and tints itself when the action is engaged.
 */
@Composable
private fun ComposerIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** Compact chip announcing the attached source image with a clear action. */
@Composable
private fun ComposerAttachmentChip(
    bitmap: Bitmap?,
    onPreview: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (bitmap != null) {
                IconButton(
                    onClick = onPreview,
                    modifier = Modifier.size(40.dp),
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.chat_generation_source_image_selected),
                        modifier = Modifier.size(32.dp),
                        contentScale = ContentScale.Crop,
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = stringResource(R.string.chat_generation_source_image_selected),
                modifier = Modifier.padding(start = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.cancel),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * Height of the picker's tag chips.
 *
 * `FilterChipDefaults.Height` is 32.dp and content padding alone cannot shrink
 * it, so the chip row stays taller than the text it holds. Pinning an exact
 * height is the only way to make a long tag list read as a dense filter strip
 * rather than a stack of buttons.
 */
private val CHAT_TAG_CHIP_HEIGHT = 26.dp

@Composable
private fun ChatGenerationTagChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = Modifier.height(CHAT_TAG_CHIP_HEIGHT),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatGenerationModelPicker(
    models: List<InstalledModelCatalog.Entry>,
    selectedModelIds: Set<String>,
    onToggle: (InstalledModelCatalog.Entry) -> Unit,
    onConfirm: () -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedTag by rememberSaveable { mutableStateOf<String?>(null) }
    // G6: the tag filter row is collapsed by default to keep the picker compact.
    var tagsExpanded by rememberSaveable { mutableStateOf(false) }
    var descriptionToShow by rememberSaveable { mutableStateOf<String?>(null) }
    val availableTags = remember(models) {
        ModelTagDerivation.collectTags(models.mapNotNull { it.model })
    }
    val filtered = remember(models, query, selectedTag, selectedModelIds) {
        models.filter { entry ->
            val tags = entry.model?.let { ModelTagDerivation.deriveTags(it) }.orEmpty()
            val matchesTag = selectedTag == null || selectedTag in tags
            val haystack = "${entry.name} ${entry.model?.description.orEmpty()} ${entry.id}"
            val matchesQuery = query.isBlank() || haystack.contains(query, ignoreCase = true)
            matchesTag && matchesQuery
        }
            // Checked models pin to the top: a multi-model batch stays in view
            // while the user keeps searching for the next one to add, instead
            // of scattering across a long alphabetical list.
            .sortedByDescending { it.id in selectedModelIds }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.chat_generation_model_search)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (query.isNotEmpty()) {
                            IconButton(
                                onClick = { query = "" },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.clear),
                                )
                            }
                        }
                        IconButton(
                            onClick = onConfirm,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(R.string.chat_generation_done),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
            )
            if (availableTags.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ChatGenerationTagChip(
                        selected = selectedTag == null,
                        label = stringResource(R.string.chat_generation_model_filter_all),
                        onClick = { selectedTag = null },
                    )
                    if (tagsExpanded) {
                        availableTags.forEach { tag ->
                            ChatGenerationTagChip(
                                selected = selectedTag == tag,
                                label = tag,
                                onClick = { selectedTag = if (selectedTag == tag) null else tag },
                            )
                        }
                    } else if (selectedTag != null) {
                        ChatGenerationTagChip(
                            selected = true,
                            label = selectedTag!!,
                            onClick = { selectedTag = null },
                        )
                    }
                    AssistChip(
                        onClick = { tagsExpanded = !tagsExpanded },
                        label = {
                            Text(
                                text = stringResource(
                                    if (tagsExpanded) {
                                        R.string.collapse_tags
                                    } else {
                                        R.string.more_tags
                                    },
                                ),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        modifier = Modifier.height(CHAT_TAG_CHIP_HEIGHT),
                        leadingIcon = {
                            Icon(
                                imageVector = if (tagsExpanded) {
                                    Icons.Default.ExpandLess
                                } else {
                                    Icons.Default.ExpandMore
                                },
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        border = null,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
            if (selectedModelIds.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            R.string.chat_generation_models_selected,
                            selectedModelIds.size,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // Icon-only on purpose: the counter next to it already
                    // says what gets cleared, so a text label would just eat
                    // width in an already dense sheet header.
                    IconButton(
                        onClick = onClearAll,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ClearAll,
                            contentDescription = stringResource(
                                R.string.chat_generation_clear_selection,
                            ),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            LazyColumn(modifier = Modifier.heightIn(max = 600.dp)) {
                items(filtered, key = { it.id }) { entry ->
                    val tags = entry.model?.let { ModelTagDerivation.deriveTags(it) }.orEmpty()
                    ListItem(
                        headlineContent = { Text(entry.name) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = entry.model?.description?.takeIf { it.isNotBlank() }
                                        ?: entry.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (tags.isNotEmpty()) {
                                    Text(
                                        text = tags.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                entry.model?.description?.takeIf { it.isNotBlank() }?.let {
                                    TextButton(
                                        onClick = { descriptionToShow = it },
                                        contentPadding = PaddingValues(
                                            horizontal = 4.dp,
                                            vertical = 0.dp,
                                        ),
                                        modifier = Modifier.height(20.dp),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.view_model_description),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                        },
                        leadingContent = {
                            Checkbox(
                                checked = entry.id in selectedModelIds,
                                onCheckedChange = null,
                            )
                        },
                        modifier = Modifier.clickable { onToggle(entry) },
                    )
                }
            }
        }
    }

    if (descriptionToShow != null) {
        AlertDialog(
            onDismissRequest = { descriptionToShow = null },
            title = { Text(stringResource(R.string.model_description_title)) },
            text = {
                Text(
                    text = descriptionToShow!!,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { descriptionToShow = null }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }
}

@Composable
private fun ChatGenerationAdvancedSettings(
    width: String,
    height: String,
    steps: String,
    cfg: String,
    seed: String,
    scheduler: String,
    onWidthChange: (String) -> Unit,
    onHeightChange: (String) -> Unit,
    onStepsChange: (String) -> Unit,
    onCfgChange: (String) -> Unit,
    onSeedChange: (String) -> Unit,
    onSchedulerChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var schedulerExpanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_generation_advanced)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatGenerationNumberField(
                        value = width,
                        onValueChange = onWidthChange,
                        label = stringResource(R.string.chat_generation_width),
                        modifier = Modifier.weight(1f),
                    )
                    ChatGenerationNumberField(
                        value = height,
                        onValueChange = onHeightChange,
                        label = stringResource(R.string.chat_generation_height),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatGenerationNumberField(
                        value = steps,
                        onValueChange = onStepsChange,
                        label = stringResource(R.string.chat_generation_steps),
                        modifier = Modifier.weight(1f),
                    )
                    ChatGenerationNumberField(
                        value = cfg,
                        onValueChange = onCfgChange,
                        label = stringResource(R.string.chat_generation_cfg),
                        allowDecimal = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                ChatGenerationNumberField(
                    value = seed,
                    onValueChange = onSeedChange,
                    label = stringResource(R.string.chat_generation_seed),
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { schedulerExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                R.string.chat_generation_scheduler_value,
                                schedulerDisplayName(scheduler),
                            ),
                        )
                    }
                    DropdownMenu(
                        expanded = schedulerExpanded,
                        onDismissRequest = { schedulerExpanded = false },
                    ) {
                        CHAT_SCHEDULERS.forEach { id ->
                            DropdownMenuItem(
                                text = { Text(schedulerDisplayName(id)) },
                                onClick = {
                                    onSchedulerChange(id)
                                    schedulerExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_generation_done))
            }
        },
    )
}

@Composable
private fun ChatGenerationNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    allowDecimal: Boolean = false,
) {
    val allowed: (String) -> Boolean = if (allowDecimal) {
        { candidate -> candidate.isEmpty() || candidate.toFloatOrNull() != null }
    } else {
        { candidate -> candidate.isEmpty() || candidate.toLongOrNull() != null }
    }
    OutlinedTextField(
        value = value,
        onValueChange = { candidate ->
            if (allowed(candidate)) {
                onValueChange(candidate)
            }
        },
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (allowDecimal) {
                KeyboardType.Decimal
            } else {
                KeyboardType.Number
            },
        ),
    )
}

private fun parseChatGenerationSettings(
    width: String,
    height: String,
    steps: String,
    cfg: String,
    seed: String,
    scheduler: String,
): ChatGenerationSettings? {
    val parsedWidth = width.toIntOrNull() ?: return null
    val parsedHeight = height.toIntOrNull() ?: return null
    val parsedSteps = steps.toIntOrNull() ?: return null
    val parsedCfg = cfg.toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
    val parsedSeed = seed.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
    if (seed.isNotBlank() && parsedSeed == null) return null
    if (parsedWidth !in 128..2048 || parsedHeight !in 128..2048) return null
    if (parsedWidth % 64 != 0 || parsedHeight % 64 != 0) return null
    if (parsedWidth.toLong() * parsedHeight > 4_194_304L) return null
    if (parsedSteps !in 1..50 || parsedCfg !in 0f..30f) return null
    if (scheduler !in CHAT_SCHEDULERS) return null
    return ChatGenerationSettings(
        width = parsedWidth,
        height = parsedHeight,
        steps = parsedSteps,
        cfg = parsedCfg,
        seed = parsedSeed,
        scheduler = scheduler,
    )
}

private val CHAT_SCHEDULERS = listOf(
    "dpm",
    "dpm_karras",
    "dpm_sde",
    "dpm_sde_karras",
    "euler",
    "euler_karras",
    "euler_a",
    "euler_a_karras",
    "lcm",
)
