package io.github.xororz.localdream.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.GenerationDefaults
import io.github.xororz.localdream.data.GenerationPreferences
import io.github.xororz.localdream.data.ModelPromptSamples
import io.github.xororz.localdream.data.ModelRepository
import io.github.xororz.localdream.data.PromptLibraryItem
import io.github.xororz.localdream.data.PromptRepository
import io.github.xororz.localdream.navigation.popBackStackIfResumed
import io.github.xororz.localdream.utils.ParamShare
import io.github.xororz.localdream.utils.schedulerDisplayName
import kotlinx.coroutines.launch

/**
 * CRUD screen for reusable positive and negative prompt pairs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptManagerScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember { PromptRepository(context) }
    val generationPreferences = remember { GenerationPreferences(context) }
    val modelRepository = remember { ModelRepository.getInstance(context) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    var query by rememberSaveable { mutableStateOf("") }
    var editingTemplate by remember { mutableStateOf<PromptLibraryItem?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var deletingTemplate by remember { mutableStateOf<PromptLibraryItem?>(null) }
    var showGlobalNegativePromptDialog by remember { mutableStateOf(false) }
    val userTemplates by remember { repository.observeAll() }
        .collectAsState(initial = emptyList())
    val globalNegativePrompt by remember { generationPreferences.observeGlobalNegativePrompt() }
        .collectAsState(initial = GenerationDefaults.DEFAULT_NEGATIVE_PROMPT)
    LaunchedEffect(modelRepository) {
        modelRepository.ensureLoaded()
    }
    val templates = remember(userTemplates, query) {
        ModelPromptSamples.libraryItems(userTemplates, query)
    }

    val saveFailedMessage = stringResource(R.string.prompt_manager_save_failed)
    val deleteFailedMessage = stringResource(R.string.prompt_manager_delete_failed)

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.prompt_manager_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStackIfResumed() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.prompt_manager_add_action),
                )
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "全局负面提示词",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = globalNegativePrompt,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { showGlobalNegativePromptDialog = true }) {
                        Text("配置全局负面提示词")
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.prompt_manager_search_hint)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(
                                    R.string.prompt_manager_clear_search,
                                ),
                            )
                        }
                    }
                },
            )

            if (templates.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(
                            if (query.isBlank()) {
                                R.string.prompt_manager_empty
                            } else {
                                R.string.prompt_manager_no_results
                            },
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(
                        items = templates,
                        key = { it.stableId },
                        contentType = { "prompt_template" },
                    ) { template ->
                        PromptTemplateCard(
                            template = template,
                            onEdit = { editingTemplate = template },
                            onDelete = { deletingTemplate = template },
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        PromptEditorDialog(
            template = null,
            onDismissRequest = { showCreateDialog = false },
            onSave = { title, prompt, negativePrompt ->
                scope.launch {
                    runCatching {
                        repository.create(title, prompt, negativePrompt)
                    }.onSuccess {
                        showCreateDialog = false
                    }.onFailure {
                        snackbarHostState.showSnackbar(saveFailedMessage)
                    }
                }
            },
        )
    }

    editingTemplate?.let { template ->
        PromptEditorDialog(
            template = template,
            onDismissRequest = { editingTemplate = null },
            onSave = { title, prompt, negativePrompt ->
                scope.launch {
                    runCatching {
                        repository.update(
                            template.templateId,
                            title,
                            prompt,
                            negativePrompt,
                        )
                    }.onSuccess { updated ->
                        if (updated == null) {
                            snackbarHostState.showSnackbar(saveFailedMessage)
                        } else {
                            editingTemplate = null
                        }
                    }.onFailure {
                        snackbarHostState.showSnackbar(saveFailedMessage)
                    }
                }
            },
        )
    }

    deletingTemplate?.let { template ->
        AlertDialog(
            onDismissRequest = { deletingTemplate = null },
            title = { Text(stringResource(R.string.prompt_manager_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.prompt_manager_delete_message,
                        template.title,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            runCatching {
                                repository.delete(template.templateId)
                            }.onSuccess { deleted ->
                                if (!deleted) {
                                    snackbarHostState.showSnackbar(deleteFailedMessage)
                                }
                            }.onFailure {
                                snackbarHostState.showSnackbar(deleteFailedMessage)
                            }
                            deletingTemplate = null
                        }
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingTemplate = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showGlobalNegativePromptDialog) {
        GlobalNegativePromptDialog(
            currentValue = globalNegativePrompt,
            onDismissRequest = { showGlobalNegativePromptDialog = false },
            onSave = { value ->
                scope.launch {
                    generationPreferences.setGlobalNegativePrompt(value)
                    showGlobalNegativePromptDialog = false
                }
            },
        )
    }
}

@Composable
private fun PromptTemplateCard(
    template: PromptLibraryItem,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = template.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.prompt_manager_edit_action),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.prompt_manager_delete_action),
                    )
                }
            }
            Text(
                text = template.prompt,
                modifier = Modifier.padding(end = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (template.negativePrompt.isNotBlank()) {
                Text(
                    text = stringResource(
                        R.string.prompt_manager_negative_summary,
                        template.negativePrompt,
                    ),
                    modifier = Modifier.padding(end = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            template.sampling?.let { sampling ->
                Text(
                    text = "${sampling.steps} 步 · CFG ${sampling.cfg} · " +
                        schedulerDisplayName(sampling.scheduler),
                    modifier = Modifier.padding(end = 16.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                text = pluralStringResource(
                    R.plurals.prompt_manager_usage_count,
                    template.useCount,
                    template.useCount,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun PromptEditorDialog(
    template: PromptLibraryItem?,
    onDismissRequest: () -> Unit,
    onSave: (title: String, prompt: String, negativePrompt: String) -> Unit,
) {
    var title by remember(template?.stableId) { mutableStateOf(template?.title.orEmpty()) }
    var prompt by remember(template?.stableId) {
        val text = template?.prompt.orEmpty()
        mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    }
    var negativePrompt by remember(template?.stableId) {
        val text = template?.negativePrompt.orEmpty()
        mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    }
    var showPromptError by remember(template?.stableId) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                stringResource(
                    if (template == null) {
                        R.string.prompt_manager_add_title
                    } else {
                        R.string.prompt_manager_edit_title
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.prompt_manager_name_label)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { candidate ->
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
                        if (prompt.text.isNotBlank()) {
                            showPromptError = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.prompt_manager_prompt_label)) },
                    minLines = 3,
                    maxLines = 7,
                    isError = showPromptError,
                    supportingText = if (showPromptError) {
                        {
                            Text(stringResource(R.string.prompt_manager_prompt_required))
                        }
                    } else {
                        null
                    },
                )
                OutlinedTextField(
                    value = negativePrompt,
                    onValueChange = { candidate ->
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
                        if (prompt.text.isNotBlank()) {
                            showPromptError = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(stringResource(R.string.prompt_manager_negative_prompt_label))
                    },
                    minLines = 2,
                    maxLines = 5,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (prompt.text.isBlank()) {
                        showPromptError = true
                    } else {
                        onSave(title, prompt.text, negativePrompt.text)
                    }
                },
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun GlobalNegativePromptDialog(
    currentValue: String,
    onDismissRequest: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(currentValue) {
        mutableStateOf(TextFieldValue(currentValue, TextRange(currentValue.length)))
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("全局负面提示词") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "在本地创作、后台服务、HTTP 和 MCP 请求未提供负面提示词时自动使用。清空后恢复内置默认值。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("负面提示词") },
                    minLines = 4,
                    maxLines = 8,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value.text) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
