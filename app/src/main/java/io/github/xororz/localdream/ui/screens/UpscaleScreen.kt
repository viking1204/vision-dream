package io.github.xororz.localdream.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.AssetOrigin
import io.github.xororz.localdream.data.DownloadProgress
import io.github.xororz.localdream.data.GenerationMode
import io.github.xororz.localdream.data.HistoryManager
import io.github.xororz.localdream.data.RemoteRepository
import io.github.xororz.localdream.data.UpscalerRepository
import io.github.xororz.localdream.navigation.popBackStackIfResumed
import io.github.xororz.localdream.remote.RemoteProtocol
import io.github.xororz.localdream.service.BackendService
import io.github.xororz.localdream.service.BackgroundGenerationService
import io.github.xororz.localdream.service.ModelDownloadService
import io.github.xororz.localdream.service.OpenAiApiService
import io.github.xororz.localdream.ui.components.BlockingProgressOverlay
import io.github.xororz.localdream.ui.components.RevealableImage
import io.github.xororz.localdream.ui.components.SmoothCircularWavyProgressIndicator
import io.github.xororz.localdream.ui.theme.Motion
import io.github.xororz.localdream.utils.UPSCALER_NATIVE_SCALE
import io.github.xororz.localdream.utils.performUpscale
import io.github.xororz.localdream.utils.saveImage
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpscaleScreen(navController: NavController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelId = "upscaler_standalone"
    val historyManager = remember { HistoryManager(context) }

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var upscaledImageUri by remember { mutableStateOf<Uri?>(null) }
    var upscaledBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isUpscaling by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val currentLog by BackendService.currentLog.collectAsState()
    val tileProgress by BackendService.tileProgress.collectAsState()

    var sharedScale by remember { mutableFloatStateOf(1f) }
    var sharedOffsetX by remember { mutableFloatStateOf(0f) }
    var sharedOffsetY by remember { mutableFloatStateOf(0f) }

    var showUpscalerDialog by remember { mutableStateOf(false) }
    val upscalerRepository = remember { UpscalerRepository.getInstance(context) }
    val remoteRepository = remember { RemoteRepository.getInstance(context) }
    // Connected-device mode snapshot at entry: the host runs its native
    // backend in upscaler mode and does the actual work; this device never
    // spawns a local process. restore() first so a process-recreated entry
    // doesn't misread a saved connection as local mode.
    val isRemote = remember {
        remoteRepository.restore()
        remoteRepository.isActive
    }
    val remoteClient = remember { if (isRemote) remoteRepository.client() else null }
    val backendHost = remoteClient?.generationHost
        ?: BackgroundGenerationService.LOCAL_BACKEND_HOST
    val availableUpscalers =
        if (isRemote) remoteRepository.remoteUpscalers() else upscalerRepository.upscalers
    LaunchedEffect(Unit) {
        if (!isRemote) upscalerRepository.ensureLoaded()
    }
    val upscalerPreferences =
        remember { context.getSharedPreferences("upscaler_prefs", Context.MODE_PRIVATE) }

    // String resources hoisted to composable scope (lint: LocalContextGetResourceValueCall).
    val msgFailedToLoadImage = stringResource(R.string.failed_to_load_image)
    val msgImageSaved = stringResource(R.string.image_saved)
    val msgDownloadDone = stringResource(R.string.download_done)
    val msgErrorDownloadFailed = stringResource(R.string.error_download_failed)
    val msgUpscaleFailed = stringResource(R.string.upscale_failed)
    val msgDownloadModelFirst = stringResource(R.string.download_model_first)
    val msgFailedToStartBackend = stringResource(R.string.failed_to_start_backend)
    val msgUnknownError = stringResource(R.string.unknown_error)
    val msgOpenAiApiUpscaleConflict = stringResource(R.string.openai_api_upscale_conflict)

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    val bitmap = context.contentResolver.openInputStream(it)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }

                    if (bitmap != null) {
                        selectedImageUri = it
                        selectedBitmap = bitmap
                        withContext(Dispatchers.Main) {
                            sharedScale = 1f
                            sharedOffsetX = 0f
                            sharedOffsetY = 0f
                        }
                    }
                } catch (e: Exception) {
                    Log.e("UpscaleScreen", "Failed to load image", e)
                    withContext(Dispatchers.Main) {
                        errorMessage = msgFailedToLoadImage.format(e.message ?: "")
                    }
                }
            }
        }
    }

    fun startUpscalerBackend() {
        if (OpenAiApiService.isRunning.value) {
            errorMessage = msgOpenAiApiUpscaleConflict
            return
        }
        try {
            context.startForegroundService(
                Intent(context, BackendService::class.java)
                    .putExtra("modelId", modelId)
                    .putExtra("backendType", BackendService.BACKEND_TYPE_UPSCALER)
                    .putExtra("width", 512)
                    .putExtra("height", 512),
            )
        } catch (e: Exception) {
            Log.e("UpscaleScreen", "Failed to start backend", e)
            errorMessage = msgFailedToStartBackend.format(e.message ?: msgUnknownError)
        }
    }

    fun stopUpscalerBackend() {
        try {
            context.startService(
                Intent(context, BackendService::class.java)
                    .setAction(BackendService.ACTION_STOP),
            )
        } catch (e: Exception) {
            Log.e("UpscaleScreen", "Failed to stop backend", e)
        }
    }

    LaunchedEffect(Unit) {
        launch(Dispatchers.IO) {
            try {
                context.cacheDir.listFiles { file ->
                    file.name.startsWith("upscaled_temp_") && file.name.endsWith(".jpg")
                }?.forEach { file ->
                    if (file.delete()) {
                        Log.d("UpscaleScreen", "Deleted temp file: ${file.name}")
                    }
                }
            } catch (e: Exception) {
                Log.e("UpscaleScreen", "Failed to clean temp files", e)
            }
        }
        if (isRemote) {
            // Process recreation lands here before the list screen's catalog
            // refresh; without it the upscaler list (and paths) stay empty.
            if (remoteRepository.upscalerPaths.isEmpty()) {
                remoteRepository.refresh()
            }
            remoteClient?.let { client ->
                client.selectModel(RemoteProtocol.UPSCALER_MODEL_ID, 512, 512)
                // Wait until the host's upscaler process answers /health so an
                // immediate upscale doesn't hit a connection refused. Bounded;
                // on timeout the upscale itself will surface the error.
                repeat(30) {
                    if (client.checkGenerationHealth()) return@let
                    delay(1000)
                }
            }
        } else {
            startUpscalerBackend()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (isRemote) {
                remoteRepository.stopHostBackendAsync(RemoteProtocol.UPSCALER_MODEL_ID)
            } else {
                stopUpscalerBackend()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.image_upscale)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStackIfResumed() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (selectedImageUri == null) {
                                    Modifier.clickable { imagePickerLauncher.launch("image/*") }
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selectedImageUri == null) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(32.dp),
                            ) {
                                val iconAlpha = remember { Animatable(0.4f) }
                                LaunchedEffect(Unit) {
                                    iconAlpha.animateTo(
                                        targetValue = 0.8f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(1200),
                                            repeatMode = RepeatMode.Reverse,
                                        ),
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = stringResource(R.string.add_image),
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = iconAlpha.value),
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    stringResource(R.string.click_to_add_image),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            ZoomableImage(
                                imageUri = selectedImageUri,
                                contentDescription = stringResource(R.string.selected_image),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp),
                                scale = sharedScale,
                                offsetX = sharedOffsetX,
                                offsetY = sharedOffsetY,
                                onTransform = { newScale, newOffsetX, newOffsetY ->
                                    sharedScale = newScale
                                    sharedOffsetX = newOffsetX
                                    sharedOffsetY = newOffsetY
                                },
                                useOriginalSize = true,
                            )
                        }

                        if (selectedImageUri != null) {
                            FilledTonalIconButton(
                                onClick = {
                                    selectedImageUri = null
                                    selectedBitmap = null
                                    sharedScale = 1f
                                    sharedOffsetX = 0f
                                    sharedOffsetY = 0f
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.clear_image),
                                )
                            }
                        }

                        if (selectedBitmap != null) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    text = "${selectedBitmap!!.width} × ${selectedBitmap!!.height}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }

                val fabEnabled = selectedBitmap != null && !isUpscaling
                val fabContainerColor by animateColorAsState(
                    targetValue = if (fabEnabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    animationSpec = tween(Motion.DurationMedium),
                    label = "FabContainerColor",
                )
                val fabContentColor by animateColorAsState(
                    targetValue = if (fabEnabled) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                    animationSpec = tween(Motion.DurationMedium),
                    label = "FabContentColor",
                )
                FloatingActionButton(
                    onClick = {
                        if (fabEnabled) {
                            showUpscalerDialog = true
                        }
                    },
                    containerColor = fabContainerColor,
                    contentColor = fabContentColor,
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoFixHigh,
                        contentDescription = stringResource(R.string.upscale),
                    )
                }

                AnimatedVisibility(
                    visible = upscaledImageUri != null,
                    enter = fadeIn(animationSpec = Motion.Fade) +
                        expandVertically(expandFrom = Alignment.Top, animationSpec = Motion.Expand),
                    exit = fadeOut(animationSpec = Motion.FadeOut) +
                        shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = Motion.Shrink),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    ElevatedCard(
                        modifier = Modifier.fillMaxSize(),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            RevealableImage(
                                revealKey = upscaledImageUri,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp),
                            ) {
                                ZoomableImage(
                                    imageUri = upscaledImageUri,
                                    contentDescription = stringResource(R.string.upscaled_image),
                                    modifier = Modifier.fillMaxSize(),
                                    scale = sharedScale,
                                    offsetX = sharedOffsetX,
                                    offsetY = sharedOffsetY,
                                    onTransform = { newScale, newOffsetX, newOffsetY ->
                                        sharedScale = newScale
                                        sharedOffsetX = newOffsetX
                                        sharedOffsetY = newOffsetY
                                    },
                                    useOriginalSize = true,
                                )
                            }

                            FilledTonalIconButton(
                                onClick = {
                                    upscaledBitmap?.let { bitmap ->
                                        scope.launch {
                                            saveImage(
                                                context = context,
                                                bitmap = bitmap,
                                                onSuccess = {
                                                    Toast.makeText(
                                                        context,
                                                        msgImageSaved,
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                },
                                                onError = { error ->
                                                    errorMessage = error
                                                },
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Save,
                                    contentDescription = stringResource(R.string.save_image),
                                )
                            }

                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    text = "${upscaledBitmap!!.width} × ${upscaledBitmap!!.height}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
                if (upscaledImageUri == null) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            // Floating Error Message
            AnimatedVisibility(
                visible = errorMessage != null,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                errorMessage?.let { msg ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        onClick = { errorMessage = null },
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                text = msg,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }

        BlockingProgressOverlay(visible = isUpscaling) {
            val progress = tileProgress
            if (progress != null) {
                val (current, total) = progress
                val fraction = current.toFloat() / total
                SmoothCircularWavyProgressIndicator(
                    progress = fraction,
                    modifier = Modifier.size(72.dp),
                )
                Text(
                    text = "${(fraction * 100).toInt()}%  $current/$total",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFeatureSettings = "tnum",
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                ContainedLoadingIndicator()
                if (currentLog.isNotEmpty()) {
                    Text(
                        text = currentLog,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }

    if (showUpscalerDialog) {
        var tempSelectedUpscalerId by remember {
            mutableStateOf(upscalerPreferences.getString("${modelId}_selected_upscaler", null))
        }
        var tempSelectedScale by remember {
            mutableStateOf(
                upscalerPreferences.getInt("${modelId}_upscale_scale", UPSCALER_NATIVE_SCALE),
            )
        }
        var downloadingUpscalerId by remember { mutableStateOf<String?>(null) }
        var downloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }

        LaunchedEffect(Unit) {
            ModelDownloadService.downloadState.collect { state ->
                when (state) {
                    is ModelDownloadService.DownloadState.Downloading -> {
                        val upscaler =
                            upscalerRepository.upscalers.find { it.id == state.modelId }
                        if (upscaler != null) {
                            downloadingUpscalerId = upscaler.id
                            downloadProgress = DownloadProgress(
                                progress = state.progress,
                                downloadedBytes = state.downloadedBytes,
                                totalBytes = state.totalBytes,
                            )
                        }
                    }

                    is ModelDownloadService.DownloadState.Success -> {
                        upscalerRepository.refreshUpscalerState(state.modelId)
                        downloadingUpscalerId = null
                        downloadProgress = null
                        Toast.makeText(
                            context,
                            msgDownloadDone,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }

                    is ModelDownloadService.DownloadState.Cancelled -> {
                        downloadingUpscalerId = null
                        downloadProgress = null
                    }

                    is ModelDownloadService.DownloadState.Error -> {
                        downloadingUpscalerId = null
                        downloadProgress = null
                        Toast.makeText(
                            context,
                            msgErrorDownloadFailed.format(state.message),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }

                    is ModelDownloadService.DownloadState.Extracting -> {
                        val upscaler =
                            upscalerRepository.upscalers.find { it.id == state.modelId }
                        if (upscaler != null) {
                            downloadingUpscalerId = upscaler.id
                            downloadProgress = null // Indeterminate progress during extraction
                        }
                    }

                    is ModelDownloadService.DownloadState.Idle -> {
                        if (downloadingUpscalerId != null && downloadProgress == null) {
                            downloadingUpscalerId = null
                        }
                    }

                    is ModelDownloadService.DownloadState.Installing,
                    is ModelDownloadService.DownloadState.AlreadyInstalled,
                    -> Unit
                }
            }
        }

        UpscalerSelectDialog(
            upscalers = availableUpscalers,
            selectedUpscalerId = tempSelectedUpscalerId,
            selectedScale = tempSelectedScale,
            downloadingUpscalerId = downloadingUpscalerId,
            downloadProgress = downloadProgress,
            onDismiss = { showUpscalerDialog = false },
            onSelectUpscaler = { upscalerId ->
                tempSelectedUpscalerId = upscalerId
            },
            onSelectScale = { scale ->
                tempSelectedScale = scale
            },
            onConfirm = {
                val selectedUpscaler =
                    availableUpscalers.find { it.id == tempSelectedUpscalerId }
                if (selectedUpscaler != null && selectedUpscaler.isDownloaded) {
                    upscalerPreferences.edit {
                        putString("${modelId}_selected_upscaler", selectedUpscaler.id)
                        putInt("${modelId}_upscale_scale", tempSelectedScale)
                    }
                    showUpscalerDialog = false

                    val targetScale = tempSelectedScale
                    selectedBitmap?.let { bitmap ->
                        BackendService.clearProgress()
                        isUpscaling = true
                        scope.launch {
                            val startedAt = System.currentTimeMillis()
                            try {
                                if (!isRemote) {
                                    var backendReady = false
                                    checkBackendHealth(
                                        backendState = BackendService.backendState,
                                        servingModelId = BackendService.servingModelId,
                                        expectedModelId = modelId,
                                        onHealthy = { backendReady = true },
                                        onUnhealthy = {},
                                    )
                                    if (!backendReady) {
                                        throw IllegalStateException(
                                            msgFailedToStartBackend.format(msgUnknownError),
                                        )
                                    }
                                }
                                val resultBitmap = performUpscale(
                                    context = context,
                                    bitmap = bitmap,
                                    upscalerId = selectedUpscaler.id,
                                    targetScale = targetScale,
                                    backendHost = backendHost,
                                    remoteUpscalerPath = if (isRemote) {
                                        remoteRepository.upscalerPaths[selectedUpscaler.id]
                                    } else {
                                        null
                                    },
                                )
                                upscaledBitmap = resultBitmap
                                historyManager.enqueueGeneratedImageSave(
                                    modelId = selectedUpscaler.id,
                                    bitmap = resultBitmap,
                                    params = GenerationParameters(
                                        steps = 0,
                                        cfg = 0f,
                                        seed = null,
                                        prompt = "",
                                        negativePrompt = "",
                                        generationTime =
                                            "${System.currentTimeMillis() - startedAt}ms",
                                        width = resultBitmap.width,
                                        height = resultBitmap.height,
                                        runOnCpu = false,
                                        scheduler = "upscale",
                                        mode = GenerationMode.UNKNOWN,
                                    ),
                                    mode = GenerationMode.UNKNOWN,
                                    upscalerId = selectedUpscaler.id,
                                    origin = if (isRemote) {
                                        AssetOrigin.REMOTE_LINK
                                    } else {
                                        AssetOrigin.LOCAL_APP
                                    },
                                ).await()

                                resultBitmap.let { bmp ->
                                    withContext(Dispatchers.IO) {
                                        try {
                                            val tempFile = File(
                                                context.cacheDir,
                                                "upscaled_temp_${System.currentTimeMillis()}.jpg",
                                            )
                                            FileOutputStream(tempFile).use { out ->
                                                bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                                            }
                                            upscaledImageUri = Uri.fromFile(tempFile)
                                        } catch (e: Exception) {
                                            Log.e("UpscaleScreen", "Failed to save temp file", e)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    msgUpscaleFailed.format(e.message ?: "Unknown error"),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } finally {
                                isUpscaling = false
                            }
                        }
                    }
                } else if (selectedUpscaler != null) {
                    Toast.makeText(
                        context,
                        msgDownloadModelFirst,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onDownload = { upscaler ->
                downloadingUpscalerId = upscaler.id
                downloadProgress = null
                upscaler.startDownload(context)
            },
        )
    }
}

// Longest-side cap for previewing high-res images. 4096 is the universal max GPU texture
// size, and 4096^2 * 4 = 67MB stays under the hardware Canvas ~100MB per-bitmap limit.
private const val MAX_DISPLAY_DIMENSION = 4096

@Composable
fun ZoomableImage(
    imageUri: Uri?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    onTransform: (scale: Float, offsetX: Float, offsetY: Float) -> Unit,
    useOriginalSize: Boolean = false,
) {
    val context = LocalContext.current

    var currentScale by remember { mutableFloatStateOf(1f) }
    var currentOffsetX by remember { mutableFloatStateOf(0f) }
    var currentOffsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(scale, offsetX, offsetY) {
        currentScale = scale
        currentOffsetX = offsetX
        currentOffsetY = offsetY
    }

    val imageRequest = remember(imageUri, useOriginalSize) {
        ImageRequest.Builder(context)
            .data(imageUri)
            .apply {
                if (useOriginalSize) {
                    // Cap the decoded preview to MAX_DISPLAY_DIMENSION on the long side.
                    // A hardware Canvas refuses to draw bitmaps over ~100MB
                    // (RecordingCanvas: "trying to draw too large bitmap"), and an upscaled
                    // result can easily exceed that (e.g. 5760x5760 = 132MB). The cap keeps
                    // the preview under the Canvas/GPU-texture limit while staying sharp under
                    // zoom; saving still uses the full-resolution bitmap.
                    size(MAX_DISPLAY_DIMENSION, MAX_DISPLAY_DIMENSION)
                    memoryCacheKey(imageUri.toString() + "_display")
                }
            }
            .build()
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, rotation ->
                    val newScale = (currentScale * zoom).coerceIn(1f, 5f)

                    val newOffsetX = currentOffsetX + pan.x
                    val newOffsetY = currentOffsetY + pan.y

                    currentScale = newScale
                    currentOffsetX = newOffsetX
                    currentOffsetY = newOffsetY

                    onTransform(newScale, newOffsetX, newOffsetY)
                }
            },
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = currentScale,
                    scaleY = currentScale,
                    translationX = currentOffsetX,
                    translationY = currentOffsetY,
                ),
            contentScale = ContentScale.Fit,
        )
    }
}
