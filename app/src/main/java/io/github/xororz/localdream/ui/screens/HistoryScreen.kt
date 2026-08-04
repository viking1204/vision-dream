package io.github.xororz.localdream.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.AssetBrowserPreferences
import io.github.xororz.localdream.data.AssetDefaultsCandidate
import io.github.xororz.localdream.data.AssetDefaultsPromotion
import io.github.xororz.localdream.data.AssetLayoutMode
import io.github.xororz.localdream.data.GenerationPreferences
import io.github.xororz.localdream.data.HistoryFilter
import io.github.xororz.localdream.data.HistoryItem
import io.github.xororz.localdream.data.HistoryManager
import io.github.xororz.localdream.data.PromptRepository
import io.github.xororz.localdream.navigation.Screen
import io.github.xororz.localdream.navigation.popBackStackIfResumed
import io.github.xororz.localdream.ui.components.AssetImageLightbox
import io.github.xororz.localdream.ui.components.GenerationParamsDialog
import io.github.xororz.localdream.ui.components.ShareParamsFlow
import io.github.xororz.localdream.utils.saveImage
import io.github.xororz.localdream.utils.saveImageFromFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Global asset browser reachable from the model list. It reuses the run
// screen's paged history and batch actions while adding persistent layout,
// global reveal, prompt-copy, and model-independent detail actions.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    navController: NavController,
    isTopLevel: Boolean = false,
    bottomBar: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val historyManager = remember { HistoryManager(context) }
    val promptRepository = remember { PromptRepository(context) }
    val generationPreferences = remember { GenerationPreferences(context) }
    val assetBrowserPreferences = remember { AssetBrowserPreferences(context) }
    val shareUseBase64 by remember { generationPreferences.observeShareUseBase64() }
        .collectAsState(initial = false)

    val msgImageSaved = stringResource(R.string.image_saved)
    val msgDeleted = stringResource(R.string.delete_success)
    val msgDeleteFailed = stringResource(R.string.delete_failed)
    val msgSavedCountWithFailed = stringResource(R.string.saved_count_with_failed)
    val msgDeletedCountWithFailed = stringResource(R.string.deleted_count_with_failed)
    val msgPromptSaved = stringResource(R.string.asset_prompt_saved)
    val msgPromptAlreadySaved = stringResource(R.string.asset_prompt_already_saved)
    val msgPromptSaveFailed = stringResource(R.string.prompt_manager_save_failed)
    val msgPromptsCopied = stringResource(R.string.asset_prompts_copied)
    val msgPromptCopied = stringResource(R.string.asset_prompt_copied)
    val msgDefaultsFailed = stringResource(R.string.asset_set_model_defaults_failed)
    val sensitiveContentDesc = stringResource(R.string.asset_sensitive_content_desc)

    val restoredFilterJson = assetBrowserPreferences.getHistoryFilterJson()
    var historyFilter by remember {
        mutableStateOf(
            restoredFilterJson?.let { HistoryFilter.fromJson(it) } ?: HistoryFilter(),
        )
    }
    LaunchedEffect(historyFilter) {
        assetBrowserPreferences.setHistoryFilterJson(historyFilter.toJson())
    }
    val pagedItems = remember(historyFilter) { historyManager.pager(historyFilter) }
        .collectAsLazyPagingItems()
    val totalCount by remember(historyFilter) { historyManager.observeCount(historyFilter) }
        .collectAsState(initial = 0)
    var layoutMode by remember { mutableStateOf(assetBrowserPreferences.layoutMode()) }
    var showLayoutMenu by remember { mutableStateOf(false) }
    var revealAll by remember { mutableStateOf(false) }
    var revealRevision by remember { mutableIntStateOf(0) }
    val itemRevealOverrides = remember { mutableStateMapOf<Long, Boolean>() }

    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var showBatchSaveDialog by remember { mutableStateOf(false) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var isBatchSaving by remember { mutableStateOf(false) }
    var batchSaveCurrent by remember { mutableIntStateOf(0) }
    var batchSaveTotal by remember { mutableIntStateOf(0) }
    var batchSaveFailed by remember { mutableIntStateOf(0) }

    var previewList by remember { mutableStateOf<List<HistoryItem>>(emptyList()) }
    var previewIndex by remember { mutableIntStateOf(0) }
    var deleteTargetItem by remember { mutableStateOf<HistoryItem?>(null) }
    var parameterItem by remember { mutableStateOf<HistoryItem?>(null) }
    var showParamsDialog by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            // G9: at top level the bottom navigation already names this screen,
            // so the app bar (title + a lone layout button) is dropped and the
            // layout switch moves into the toolbar row below.
            if (!isTopLevel) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.asset_manager_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStackIfResumed() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                stringResource(R.string.back),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
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
            Surface(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = sensitiveContentDesc
                    },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            revealAll = !revealAll
                            revealRevision++
                            itemRevealOverrides.clear()
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = if (revealAll) {
                                Icons.Default.Visibility
                            } else {
                                Icons.Default.VisibilityOff
                            },
                            contentDescription = stringResource(R.string.asset_nsfw_toggle),
                            tint = if (revealAll) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.asset_count, totalCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box {
                        IconButton(
                            onClick = { showLayoutMenu = true },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.ViewModule,
                                contentDescription = stringResource(
                                    R.string.asset_layout_action,
                                ),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(
                            expanded = showLayoutMenu,
                            onDismissRequest = { showLayoutMenu = false },
                        ) {
                            AssetLayoutMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                when (mode) {
                                                    AssetLayoutMode.WATERFALL ->
                                                        R.string.asset_layout_waterfall

                                                    AssetLayoutMode.LIST ->
                                                        R.string.asset_layout_list

                                                    AssetLayoutMode.GRID ->
                                                        R.string.asset_layout_grid
                                                },
                                            ),
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = when (mode) {
                                                AssetLayoutMode.WATERFALL ->
                                                    Icons.AutoMirrored.Filled.ViewQuilt

                                                AssetLayoutMode.LIST ->
                                                    Icons.AutoMirrored.Filled.ViewList

                                                AssetLayoutMode.GRID ->
                                                    Icons.Default.GridView
                                            },
                                            contentDescription = null,
                                        )
                                    },
                                    trailingIcon = if (layoutMode == mode) {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        layoutMode = mode
                                        assetBrowserPreferences.setLayoutMode(mode)
                                        showLayoutMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
            ModelRunHistoryPage(
                historyFilter = historyFilter,
                currentModelId = null,
                pagedItems = pagedItems,
                totalCount = totalCount,
                isSelectionMode = isSelectionMode,
                selectedIds = selectedIds.toSet(),
                isBatchSaving = isBatchSaving,
                onFilterChange = { historyFilter = it },
                onShowFilterSheet = {},
                onItemClick = { item ->
                    if (isSelectionMode) {
                        if (item.id in selectedIds) {
                            selectedIds.remove(item.id)
                            if (selectedIds.isEmpty()) {
                                isSelectionMode = false
                            }
                        } else {
                            selectedIds.add(item.id)
                        }
                    } else {
                        val list = pagedItems.itemSnapshotList.items.filterNotNull()
                        previewIndex = list.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                        previewList = list
                    }
                },
                onItemLongClick = { item ->
                    if (!isSelectionMode) {
                        isSelectionMode = true
                        selectedIds.clear()
                        selectedIds.add(item.id)
                    }
                },
                onExitSelection = {
                    isSelectionMode = false
                    selectedIds.clear()
                },
                onToggleSelectAll = {
                    // Select-all spans every match, not just loaded pages, so it
                    // queries the full id set rather than reading the grid.
                    if (totalCount > 0 && selectedIds.size >= totalCount) {
                        selectedIds.clear()
                        isSelectionMode = false
                    } else {
                        scope.launch {
                            val allIds = historyManager.queryIds(historyFilter)
                            selectedIds.clear()
                            selectedIds.addAll(allIds)
                        }
                    }
                },
                onBatchSave = { showBatchSaveDialog = true },
                onBatchDelete = { showBatchDeleteDialog = true },
                layoutMode = layoutMode,
                revealAll = revealAll,
                revealRevision = revealRevision,
                itemRevealOverrides = itemRevealOverrides,
                onItemRevealChanged = { id, revealed ->
                    if (revealed == revealAll) {
                        itemRevealOverrides.remove(id)
                    } else {
                        itemRevealOverrides[id] = revealed
                    }
                },
                onItemInfoClick = { item ->
                    parameterItem = item
                    showParamsDialog = true
                },
                onGoCreate = {
                    navController.navigate(Screen.ChatGeneration.route)
                },
                initialScroll = remember {
                    assetBrowserPreferences.getAssetScrollIndex() to
                        assetBrowserPreferences.getAssetScrollOffset()
                },
                onAssetScroll = { index, offset ->
                    assetBrowserPreferences.setAssetScroll(index, offset)
                },
            )
        }
    }

    if (previewList.isNotEmpty()) {
        AssetImageLightbox(
            items = previewList,
            initialIndex = previewIndex,
            onDismiss = { previewList = emptyList() },
            onShowInfo = { item ->
                parameterItem = item
                showParamsDialog = true
            },
            onToggleFavorite = { item ->
                scope.launch(Dispatchers.IO) {
                    historyManager.setFavorite(item.id, !item.favorite)
                }
            },
            onSave = { item, bmp ->
                if (bmp != null) {
                    scope.launch {
                        saveImage(
                            context = context,
                            bitmap = bmp,
                            onSuccess = {
                                Toast.makeText(context, msgImageSaved, Toast.LENGTH_SHORT).show()
                            },
                            onError = { errorMsg ->
                                Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                }
            },
            onDelete = { item ->
                deleteTargetItem = item
                showDeleteDialog = true
            },
        )
    }

    deleteTargetItem?.let { item ->
        if (showDeleteDialog) {
            ModelRunConfirmDialog(
                title = stringResource(R.string.delete_image),
                text = stringResource(R.string.delete_image_confirm),
                confirmText = stringResource(R.string.delete),
                destructiveConfirm = true,
                onConfirm = {
                    scope.launch {
                        val success = historyManager.deleteHistoryItem(item)
                        showDeleteDialog = false
                        deleteTargetItem = null
                        if (success) {
                            previewList = emptyList()
                            Toast.makeText(context, msgDeleted, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, msgDeleteFailed, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onDismiss = {
                    showDeleteDialog = false
                    deleteTargetItem = null
                },
            )
        }
    }

    parameterItem?.let { item ->
        if (showParamsDialog) {
            GenerationParamsDialog(
                title = stringResource(R.string.generation_params_title),
                params = item.params,
                modelId = item.modelId,
                displayMode = item.mode,
                showImg2imgButton = false,
                showReproduceButton = false,
                onSavePrompt = {
                    scope.launch {
                        runCatching {
                            val existing = promptRepository.findExact(
                                item.params.prompt,
                                item.params.negativePrompt,
                            )
                            if (existing == null) {
                                promptRepository.create(
                                    title = "",
                                    prompt = item.params.prompt,
                                    negativePrompt = item.params.negativePrompt,
                                )
                            }
                            existing
                        }.onSuccess { existing ->
                            Toast.makeText(
                                context,
                                if (existing == null) {
                                    msgPromptSaved
                                } else {
                                    msgPromptAlreadySaved
                                },
                                Toast.LENGTH_SHORT,
                            ).show()
                        }.onFailure {
                            Toast.makeText(
                                context,
                                msgPromptSaveFailed,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
                onCopyPrompts = {
                    copyPromptPairToClipboard(context, item, msgPromptsCopied)
                },
                onCopyPrompt = {
                    copySinglePrompt(context, item.params.prompt, msgPromptCopied)
                },
                onCopyNegativePrompt = {
                    copySinglePrompt(context, item.params.negativePrompt, msgPromptCopied)
                },
                onShare = {
                    showParamsDialog = false
                    showShareDialog = true
                },
                onSendToImg2img = {},
                onSetAsModelDefaults = {
                    scope.launch {
                        runCatching {
                            val current = generationPreferences.getPreferences(item.modelId).first()
                            val candidate = AssetDefaultsCandidate(
                                prompt = item.params.prompt,
                                negativePrompt = item.params.negativePrompt,
                                steps = item.params.steps,
                                cfg = item.params.cfg,
                                width = item.params.width,
                                height = item.params.height,
                                scheduler = item.params.scheduler,
                            )
                            val promoted = AssetDefaultsPromotion.promote(current, candidate)
                            generationPreferences.saveAllFields(
                                modelId = item.modelId,
                                prompt = promoted.prompt,
                                negativePrompt = promoted.negativePrompt,
                                steps = promoted.steps,
                                cfg = promoted.cfg,
                                seed = current.seed,
                                width = promoted.width,
                                height = promoted.height,
                                denoiseStrength = current.denoiseStrength,
                                useOpenCL = current.useOpenCL,
                                batchCounts = current.batchCounts,
                                scheduler = promoted.scheduler,
                                aspectRatio = current.aspectRatio,
                            )
                        }.onSuccess {
                            Toast.makeText(
                                context,
                                resources.getString(
                                    R.string.asset_set_model_defaults_done,
                                    item.modelId,
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }.onFailure {
                            Toast.makeText(context, msgDefaultsFailed, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onReproduce = {},
                onDismiss = {
                    showParamsDialog = false
                    parameterItem = null
                },
            )
        }

        if (showShareDialog) {
            ShareParamsFlow(
                source = item.params,
                modelId = item.modelId,
                useBase64Initial = shareUseBase64,
                onUseBase64Changed = { value ->
                    scope.launch { generationPreferences.setShareUseBase64(value) }
                },
                onDismiss = {
                    showShareDialog = false
                    parameterItem = null
                },
            )
        }
    }

    if (showBatchSaveDialog && selectedIds.isNotEmpty()) {
        ModelRunConfirmDialog(
            title = stringResource(R.string.batch_save),
            text = pluralStringResource(
                R.plurals.batch_save_confirm,
                selectedIds.size,
                selectedIds.size,
            ),
            confirmText = stringResource(R.string.yes),
            onConfirm = {
                val ids = selectedIds.toList()
                showBatchSaveDialog = false
                if (ids.isNotEmpty()) {
                    batchSaveTotal = ids.size
                    batchSaveCurrent = 0
                    batchSaveFailed = 0
                    isBatchSaving = true
                    scope.launch(Dispatchers.IO) {
                        // Resolve the selection to items; an id missing here
                        // (deleted meanwhile) counts as a failure.
                        val items = historyManager.getItems(ids)
                        val missing = ids.size - items.size
                        if (missing > 0) {
                            withContext(Dispatchers.Main) {
                                batchSaveFailed += missing
                                batchSaveCurrent += missing
                            }
                        }
                        items.forEach { item ->
                            var success = false
                            if (item.imageFile.exists()) {
                                saveImageFromFile(
                                    context = context,
                                    sourceFile = item.imageFile,
                                    onSuccess = { success = true },
                                    onError = { },
                                )
                            }
                            withContext(Dispatchers.Main) {
                                batchSaveCurrent += 1
                                if (!success) batchSaveFailed += 1
                            }
                        }
                        withContext(Dispatchers.Main) {
                            val failed = batchSaveFailed
                            val saved = batchSaveTotal - failed
                            val message = if (failed == 0) {
                                resources.getQuantityString(
                                    R.plurals.saved_count,
                                    saved,
                                    saved,
                                )
                            } else {
                                msgSavedCountWithFailed.format(saved, failed)
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            isBatchSaving = false
                            selectedIds.clear()
                            isSelectionMode = false
                        }
                    }
                }
            },
            onDismiss = { showBatchSaveDialog = false },
        )
    }

    if (isBatchSaving) {
        BatchSaveProgressDialog(current = batchSaveCurrent, total = batchSaveTotal)
    }

    if (showBatchDeleteDialog && selectedIds.isNotEmpty()) {
        ModelRunConfirmDialog(
            title = stringResource(R.string.batch_delete),
            text = pluralStringResource(
                R.plurals.batch_delete_confirm,
                selectedIds.size,
                selectedIds.size,
            ),
            confirmText = stringResource(R.string.delete),
            destructiveConfirm = true,
            onConfirm = {
                val ids = selectedIds.toList()
                showBatchDeleteDialog = false
                scope.launch {
                    val itemsToDelete = historyManager.getItems(ids)
                    val successCount = historyManager.deleteHistoryItems(itemsToDelete)
                    val failCount = ids.size - successCount
                    selectedIds.clear()
                    isSelectionMode = false

                    val message = if (failCount == 0) {
                        resources.getQuantityString(
                            R.plurals.deleted_count,
                            successCount,
                            successCount,
                        )
                    } else {
                        msgDeletedCountWithFailed.format(successCount, failCount)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showBatchDeleteDialog = false },
        )
    }
}

private fun copyPromptPairToClipboard(
    context: Context,
    item: HistoryItem,
    confirmationMessage: String,
) {
    val payload = buildString {
        append("正向: ")
        append(item.params.prompt)
        append("\n负面: ")
        append(item.params.negativePrompt)
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(
        ClipData.newPlainText("Vision Dream prompts", payload),
    )
    Toast.makeText(context, confirmationMessage, Toast.LENGTH_SHORT).show()
}

private fun copySinglePrompt(
    context: Context,
    prompt: String,
    confirmationMessage: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(
        ClipData.newPlainText("Vision Dream prompt", prompt),
    )
    Toast.makeText(context, confirmationMessage, Toast.LENGTH_SHORT).show()
}
