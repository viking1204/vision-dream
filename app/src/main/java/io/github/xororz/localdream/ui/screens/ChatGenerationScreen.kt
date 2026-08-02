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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import io.github.xororz.localdream.navigation.Screen
import io.github.xororz.localdream.navigation.popBackStackIfResumed
import io.github.xororz.localdream.openai.BackendRuntimeCoordinator
import io.github.xororz.localdream.openai.ImageRequestParameters
import io.github.xororz.localdream.openai.InferenceArbiter
import io.github.xororz.localdream.openai.InstalledModelCatalog
import io.github.xororz.localdream.openai.NativeBackendClient
import io.github.xororz.localdream.service.BackendService
import io.github.xororz.localdream.service.NativeRuntimeAttestationRecorder
import io.github.xororz.localdream.ui.components.NegativePromptToggle
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

/** Generation modes exposed by the chat composer. Keeps prompt and model
 *  context when switched; image-based modes require a source image. */
private enum class ChatMode(val key: String) {
    TXT2IMG("TXT2IMG"),
    IMG2IMG("IMG2IMG"),
    INPAINT("INPAINT"),
    UPSCALE("UPSCALE"),
    ;

    val needsSourceImage: Boolean get() = this != TXT2IMG

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

            !InferenceArbiter.process.tryAcquireForApp() -> {
                messages += ChatGenerationMessage.Error(nextId(), busyMessage)
            }

            else -> {
                val submittedNegativePrompt = negativePrompt.text.trim().ifBlank {
                    globalNegativePrompt
                }
                val sourceImage = sourceImageBytes
                messages += ChatGenerationMessage.User(nextId(), submittedPrompt)
                prompt = TextFieldValue()
                keyboardController?.hide()
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
                                mode = chatMode.toGenerationMode(),
                            ),
                            mode = chatMode.toGenerationMode(),
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
            enabled = !isGenerating,
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

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .imePadding(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChatModeSwitcher(
                current = chatMode,
                onModeChange = onModeChange,
                enabled = !isGenerating,
                modifier = Modifier.fillMaxWidth(),
            )
            if (chatMode.needsSourceImage) {
                SourceImageRow(
                    sourceImageBytes = sourceImageBytes,
                    onPick = onPickSourceImage,
                    onClear = onClearSourceImage,
                    enabled = !isGenerating,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onModelClick,
                    enabled = hasModels && !isGenerating,
                    modifier = Modifier.weight(1f),
                ) {
                    if (selectedModelName != null) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = when {
                            selectedModelName == null -> stringResource(R.string.chat_generation_select_model)
                            selectedModelBackend != null -> "$selectedModelName · $selectedModelBackend"
                            else -> selectedModelName
                        },
                        modifier = Modifier.padding(start = 6.dp),
                        maxLines = 1,
                    )
                }
                IconButton(
                    onClick = onPromptPickerClick,
                    enabled = !isGenerating,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Bookmarks,
                        contentDescription = stringResource(
                            R.string.chat_generation_prompt_library,
                        ),
                    )
                }
                IconButton(
                    onClick = onAdvancedSettingsClick,
                    enabled = !isGenerating,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = stringResource(R.string.chat_generation_advanced),
                    )
                }
            }
            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isGenerating,
                label = { Text(stringResource(R.string.chat_generation_prompt)) },
                minLines = 2,
                maxLines = 4,
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NegativePromptToggle(
                    expanded = negativePromptExpanded,
                    hasValue = negativePrompt.text.isNotBlank(),
                    onExpandedChange = { negativePromptExpanded = it },
                    modifier = Modifier.weight(1f),
                    enabled = !isGenerating,
                )
                FilledIconButton(
                    onClick = onSend,
                    enabled = prompt.text.isNotBlank() && hasModels && !isGenerating,
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.chat_generation_send),
                    )
                }
            }
            if (negativePromptExpanded) {
                OutlinedTextField(
                    value = negativePrompt,
                    onValueChange = onNegativePromptChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isGenerating,
                    label = {
                        Text(stringResource(R.string.chat_generation_negative_prompt))
                    },
                    minLines = 2,
                    maxLines = 4,
                    shape = MaterialTheme.shapes.large,
                )
            }
        }
    }
}

@Composable
private fun ChatModeSwitcher(
    current: ChatMode,
    onModeChange: (ChatMode) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChatMode.entries.forEach { mode ->
            FilterChip(
                selected = mode == current,
                onClick = { if (enabled) onModeChange(mode) },
                enabled = enabled,
                label = {
                    Text(
                        text = stringResource(
                            when (mode) {
                                ChatMode.TXT2IMG -> R.string.chat_generation_mode_txt2img
                                ChatMode.IMG2IMG -> R.string.chat_generation_mode_img2img
                                ChatMode.INPAINT -> R.string.chat_generation_mode_inpaint
                                ChatMode.UPSCALE -> R.string.chat_generation_mode_upscale
                            },
                        ),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

@Composable
private fun SourceImageRow(
    sourceImageBytes: ByteArray?,
    onPick: () -> Unit,
    onClear: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(
            onClick = onPick,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = if (sourceImageBytes != null) {
                    stringResource(R.string.chat_generation_source_image_selected)
                } else {
                    stringResource(R.string.chat_generation_select_source_image)
                },
                modifier = Modifier.padding(start = 6.dp),
                maxLines = 1,
            )
        }
        if (sourceImageBytes != null) {
            IconButton(onClick = onClear, enabled = enabled) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.cancel),
                )
            }
        }
    }
}

@Composable
private fun ChatGenerationModelPicker(
    models: List<InstalledModelCatalog.Entry>,
    selectedModelId: String?,
    onSelect: (InstalledModelCatalog.Entry) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_generation_select_model)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                items(models, key = { it.id }) { model ->
                    ListItem(
                        headlineContent = { Text(model.name) },
                        supportingContent = { Text(model.id) },
                        leadingContent = {
                            RadioButton(
                                selected = model.id == selectedModelId,
                                onClick = null,
                            )
                        },
                        modifier = Modifier.clickable { onSelect(model) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun ChatGenerationAdvancedSettings(
    width: String,
    height: String,
    steps: String,
    cfg: String,
    seed: String,
    scheduler: String,
    enabled: Boolean,
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
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                    ChatGenerationNumberField(
                        value = height,
                        onValueChange = onHeightChange,
                        label = stringResource(R.string.chat_generation_height),
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatGenerationNumberField(
                        value = steps,
                        onValueChange = onStepsChange,
                        label = stringResource(R.string.chat_generation_steps),
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                    ChatGenerationNumberField(
                        value = cfg,
                        onValueChange = onCfgChange,
                        label = stringResource(R.string.chat_generation_cfg),
                        enabled = enabled,
                        allowDecimal = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                ChatGenerationNumberField(
                    value = seed,
                    onValueChange = onSeedChange,
                    label = stringResource(R.string.chat_generation_seed),
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { schedulerExpanded = true },
                        enabled = enabled,
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
    enabled: Boolean,
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
        enabled = enabled,
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
