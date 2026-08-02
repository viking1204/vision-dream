package io.github.xororz.localdream.ui.screens

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PhotoFilter
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.AssetOrigin
import io.github.xororz.localdream.data.CreationDraft
import io.github.xororz.localdream.data.GenerationDefaults
import io.github.xororz.localdream.data.GenerationMode
import io.github.xororz.localdream.data.GenerationPreferences
import io.github.xororz.localdream.data.HistoryManager
import io.github.xororz.localdream.data.ModelTagDerivation
import io.github.xororz.localdream.navigation.Screen
import io.github.xororz.localdream.navigation.popBackStackIfResumed
import io.github.xororz.localdream.openai.BackendRuntimeCoordinator
import io.github.xororz.localdream.openai.ImageRequestParameters
import io.github.xororz.localdream.openai.InferenceArbiter
import io.github.xororz.localdream.openai.InstalledModelCatalog
import io.github.xororz.localdream.openai.NativeBackendClient
import io.github.xororz.localdream.service.BackendService
import io.github.xororz.localdream.service.NativeRuntimeAttestationRecorder
import io.github.xororz.localdream.ui.components.PromptPickerDialog
import io.github.xororz.localdream.ui.components.RevealableImage
import io.github.xororz.localdream.utils.ParamShare
import io.github.xororz.localdream.utils.schedulerDisplayName
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ChatGenerationSettings(
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
private class PendingChatRequest(
    val entry: InstalledModelCatalog.Entry,
    val prompt: String,
    val negativePrompt: String,
    val settings: ChatGenerationSettings,
    val chatMode: ChatMode,
    val sourceImage: ByteArray?,
)

/** Generation modes exposed by the chat composer. Keeps prompt and model
 *  context when switched; image-based modes require a source image. */
private enum class ChatMode(val key: String) {
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
    val backendClient = remember { NativeBackendClient() }
    val historyManager = remember { HistoryManager(context) }
    val generationPreferences = remember { GenerationPreferences(context) }
    val messages = remember { mutableStateListOf<ChatGenerationMessage>() }
    val listState = rememberLazyListState()

    var installedModels by remember {
        mutableStateOf<List<InstalledModelCatalog.Entry>>(emptyList())
    }
    var modelsLoading by remember { mutableStateOf(true) }
    var modelLoadError by remember { mutableStateOf<String?>(null) }
    var selectedModelId by rememberSaveable { mutableStateOf<String?>(null) }
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
    var isGenerating by remember { mutableStateOf(false) }
    val pendingQueue = remember { mutableStateListOf<PendingChatRequest>() }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var nextMessageId by remember { mutableLongStateOf(0L) }

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

    val visibleMessages = remember(messages, visibleMessageCount) {
        if (messages.size <= visibleMessageCount) {
            messages
        } else {
            messages.takeLast(visibleMessageCount)
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

    val selectedModel = installedModels.firstOrNull { it.id == selectedModelId }
    val busyMessage = stringResource(R.string.chat_generation_busy)
    val genericError = stringResource(R.string.chat_generation_error)
    val invalidSettings = stringResource(R.string.chat_generation_invalid_settings)
    val sourceImageRequired = stringResource(R.string.chat_generation_source_image_required)

    fun nextId(): Long {
        nextMessageId += 1L
        return nextMessageId
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
            val preferred = entries.firstOrNull { it.id == selectedModelId }
                ?: entries.firstOrNull { it.id == BackendService.servingModelId.value }
                ?: entries.firstOrNull()
            if (preferred != null && preferred.id != selectedModelId) {
                selectedModelId = preferred.id
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
            draft.modelId?.let { id -> selectedModelId = id }
        }
    }

    // 恢复上一次的创作对话，让创作历史在进程被杀/App 重启后仍能保留。
    // 图片复用统一资产管理器已落盘的文件；文件已不存在的图会被丢弃。
    LaunchedEffect(Unit) {
        generationPreferences.getChatHistoryJson()?.let { raw ->
            chatHistoryFromJson(raw)?.let { restored ->
                if (restored.isNotEmpty()) {
                    messages.addAll(restored)
                    nextMessageId = restored.maxOf { it.id } + 1L
                }
            }
        }
    }

    LaunchedEffect(
        prompt.text,
        negativePrompt.text,
        selectedModelId,
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
                modelId = selectedModelId,
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

    LaunchedEffect(messages.size, isGenerating, visibleMessageCount) {
        val lastIndex = visibleMessages.size + if (isGenerating) 0 else -1
        if (lastIndex >= 0) {
            listState.animateScrollToItem(lastIndex)
        }
    }

    DisposableEffect(backendClient) {
        onDispose {
            // Cancelling the socket makes the native backend abandon a result
            // that no longer has a visible consumer. The job's finally block
            // still releases the process-wide app inference lease.
            backendClient.cancelAll()
            activeJob?.cancel()
        }
    }

    fun startGeneration(request: PendingChatRequest) {
        if (!InferenceArbiter.process.tryAcquireForApp()) {
            messages += ChatGenerationMessage.Error(nextId(), busyMessage)
            return
        }
        val entry = request.entry
        val settings = request.settings
        val submittedPrompt = request.prompt
        val submittedNegativePrompt = request.negativePrompt
        val sourceImage = request.sourceImage
        val requestMode = request.chatMode
        isGenerating = true
        activeJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val dimensions = coordinator.ensureReady(
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
                            NativeRuntimeAttestationRecorder.record(context, entry.id)
                        },
                    )
                }
                val generationTime = (
                    (SystemClock.elapsedRealtime() - startedAt) / 1000f
                    ).let { "%.1fs".format(it) }
                val saved = historyManager.enqueueEncodedImageSave(
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
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                messages += ChatGenerationMessage.Error(
                    id = nextId(),
                    message = error.message ?: genericError,
                )
            } finally {
                InferenceArbiter.process.releaseFromApp()
                isGenerating = false
                activeJob = null
            }
        }
    }

    val submitGeneration = {
        val entry = selectedModel
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
            entry == null -> {
                messages += ChatGenerationMessage.Error(nextId(), genericError)
            }

            submittedPrompt.isEmpty() -> Unit

            settings == null -> {
                messages += ChatGenerationMessage.Error(nextId(), invalidSettings)
            }

            chatMode.needsSourceImage && sourceImageBytes == null -> {
                messages += ChatGenerationMessage.Error(nextId(), sourceImageRequired)
            }

            else -> {
                val request = PendingChatRequest(
                    entry = entry,
                    prompt = submittedPrompt,
                    negativePrompt = negativePrompt.text.trim().ifBlank { globalNegativePrompt },
                    settings = settings,
                    chatMode = chatMode,
                    sourceImage = sourceImageBytes,
                )
                messages += ChatGenerationMessage.User(nextId(), submittedPrompt)
                prompt = TextFieldValue()
                keyboardController?.hide()
                // The composer stays editable during a run, so extra submissions
                // queue up instead of being rejected by the inference arbiter.
                if (isGenerating) {
                    pendingQueue += request
                } else {
                    startGeneration(request)
                }
            }
        }
    }

    // Drains the queue on the idle edge: exactly one request starts per
    // completed run, so the native backend never sees concurrent sessions.
    LaunchedEffect(isGenerating, pendingQueue.size) {
        if (!isGenerating && pendingQueue.isNotEmpty()) {
            startGeneration(pendingQueue.removeAt(0))
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(
                            if (isTopLevel) {
                                R.string.studio_nav_create
                            } else {
                                R.string.chat_generation_title
                            },
                        ),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    if (!isTopLevel) {
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
                                selectedModelName = selectedModel?.name,
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
                    ChatGenerationMessageItem(message)
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
            ChatGenerationComposer(
                selectedModelName = selectedModel?.name,
                selectedModelBackend = selectedModel?.model?.runOnCpu?.let { if (it) "CPU" else "NPU" },
                prompt = prompt,
                negativePrompt = negativePrompt,
                isGenerating = isGenerating,
                pendingCount = pendingQueue.size,
                hasModels = installedModels.isNotEmpty(),
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
            selectedModelId = selectedModelId,
            onSelect = { entry ->
                selectedModelId = entry.id
                applyModelDefaults(entry)
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
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
            modelId = selectedModelId,
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
) {
    when (message) {
        is ChatGenerationMessage.User -> {
            Row(
                modifier = modifier.fillMaxWidth(),
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
            Row(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
            ) {
                Card(modifier = Modifier.fillMaxWidth(0.9f)) {
                    RevealableImage(
                        revealKey = message.id,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(
                                message.width.toFloat() /
                                    message.height.coerceAtLeast(1).toFloat(),
                            ),
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
                    Text(
                        text = stringResource(
                            R.string.chat_generation_image_metadata,
                            message.modelName,
                            message.seed?.toString()
                                ?: stringResource(R.string.chat_generation_random_seed),
                        ),
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        is ChatGenerationMessage.Error -> {
            Row(
                modifier = modifier.fillMaxWidth(),
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

@Composable
private fun ChatGenerationComposer(
    selectedModelName: String?,
    selectedModelBackend: String? = null,
    prompt: TextFieldValue,
    negativePrompt: TextFieldValue,
    isGenerating: Boolean,
    pendingCount: Int,
    hasModels: Boolean,
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
    var showMoreMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .imePadding(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (sourceImageBytes != null) {
                ComposerAttachmentChip(onClear = onClearSourceImage)
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
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 8.dp, bottom = 6.dp),
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
                        Box {
                            ComposerIconButton(
                                icon = Icons.Default.MoreHoriz,
                                contentDescription = stringResource(
                                    R.string.chat_generation_more_actions,
                                ),
                                onClick = { showMoreMenu = true },
                                active = negativePromptExpanded,
                            )
                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                R.string.chat_generation_prompt_library,
                                            ),
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Bookmarks, contentDescription = null)
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        onPromptPickerClick()
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(R.string.chat_generation_advanced))
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Tune, contentDescription = null)
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        onAdvancedSettingsClick()
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                R.string.chat_generation_negative_prompt,
                                            ),
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Block, contentDescription = null)
                                    },
                                    trailingIcon = {
                                        if (negativePromptExpanded ||
                                            negativePrompt.text.isNotBlank()
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                            )
                                        }
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        negativePromptExpanded = !negativePromptExpanded
                                    },
                                )
                            }
                        }
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
                            enabled = prompt.text.isNotBlank() && hasModels,
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
}

/** Borderless multi-line field used inside the composer card. */
@Composable
private fun ComposerTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    imeAction: ImeAction,
    onSend: (() -> Unit)?,
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
        minLines = 1,
        maxLines = 5,
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
    IconButton(onClick = onClick, enabled = enabled) {
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
private fun ComposerAttachmentChip(onClear: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatGenerationModelPicker(
    models: List<InstalledModelCatalog.Entry>,
    selectedModelId: String?,
    onSelect: (InstalledModelCatalog.Entry) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedTag by rememberSaveable { mutableStateOf<String?>(null) }
    val availableTags = remember(models) {
        ModelTagDerivation.collectTags(models.mapNotNull { it.model })
    }
    val filtered = remember(models, query, selectedTag) {
        models.filter { entry ->
            val tags = entry.model?.let { ModelTagDerivation.deriveTags(it) }.orEmpty()
            val matchesTag = selectedTag == null || selectedTag in tags
            val haystack = "${entry.name} ${entry.model?.description.orEmpty()} ${entry.id}"
            val matchesQuery = query.isBlank() || haystack.contains(query, ignoreCase = true)
            matchesTag && matchesQuery
        }
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
            Text(
                text = stringResource(R.string.chat_generation_select_model),
                style = MaterialTheme.typography.titleMedium,
            )
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
                singleLine = true,
                shape = MaterialTheme.shapes.large,
            )
            if (availableTags.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedTag == null,
                        onClick = { selectedTag = null },
                        label = {
                            Text(stringResource(R.string.chat_generation_model_filter_all))
                        },
                    )
                    availableTags.forEach { tag ->
                        FilterChip(
                            selected = selectedTag == tag,
                            onClick = { selectedTag = if (selectedTag == tag) null else tag },
                            label = { Text(tag) },
                        )
                    }
                }
            }
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(filtered, key = { it.id }) { entry ->
                    val tags = entry.model?.let { ModelTagDerivation.deriveTags(it) }.orEmpty()
                    ListItem(
                        headlineContent = { Text(entry.name) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = entry.model?.description?.takeIf { it.isNotBlank() }
                                        ?: entry.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                )
                                if (tags.isNotEmpty()) {
                                    Text(
                                        text = tags.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        },
                        leadingContent = {
                            RadioButton(
                                selected = entry.id == selectedModelId,
                                onClick = null,
                            )
                        },
                        modifier = Modifier.clickable { onSelect(entry) },
                    )
                }
            }
        }
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
