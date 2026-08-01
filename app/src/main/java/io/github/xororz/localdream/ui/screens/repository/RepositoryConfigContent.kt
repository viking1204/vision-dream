package io.github.xororz.localdream.ui.screens.repository

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R
import io.github.xororz.localdream.modelcatalog.RepositoryConfig
import io.github.xororz.localdream.modelcatalog.RepositoryType

/**
 * Pure UI surface for managing user-configured model repositories. All state
 * mutations are routed back through [onEvent] so the hosting screen owns
 * persistence; the dialog performs URL validation (must start with http:// or
 * https://) and duplicate-URL detection inline so the rules stay testable
 * without a host.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepositoryConfigContent(
    state: RepositoryConfigUiState,
    onEvent: (RepositoryConfigEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<RepositoryConfig?>(null) }
    val showDialog = state.showAddDialog || state.editingRepository != null

    Scaffold(
        modifier = modifier.testTag("repository-config-content"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.repository_config_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEvent(RepositoryConfigEvent.ShowAddDialog) },
                modifier = Modifier.testTag("repository-add"),
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.repository_add)) },
            )
        },
    ) { padding ->
        if (state.repositories.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.repository_add),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    stringResource(R.string.repository_config_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 12.dp,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.repositories, key = { it.id }) { repository ->
                    RepositoryCard(
                        repository = repository,
                        onToggle = { onEvent(RepositoryConfigEvent.ToggleEnabled(repository)) },
                        onEdit = { onEvent(RepositoryConfigEvent.StartEdit(repository)) },
                        onDelete = { pendingDelete = repository },
                    )
                }
            }
        }
    }

    if (showDialog) {
        RepositoryEditDialog(
            state = state,
            onEvent = onEvent,
        )
    }

    pendingDelete?.let { repository ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(repository.name) },
            text = { Text(stringResource(R.string.repository_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onEvent(RepositoryConfigEvent.Delete(repository))
                        pendingDelete = null
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun RepositoryCard(
    repository: RepositoryConfig,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        repository.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        repository.baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = repository.enabled,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.testTag("repository-toggle-${repository.id}"),
                )
            }
            AssistChip(
                onClick = {},
                label = { Text(repository.type.name) },
                border = null,
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑")
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("repository-delete-${repository.id}"),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepositoryEditDialog(
    state: RepositoryConfigUiState,
    onEvent: (RepositoryConfigEvent) -> Unit,
) {
    val typeOptions = remember { RepositoryType.entries.toList() }
    var typeExpanded by remember { mutableStateOf(false) }

    val urlInput = state.urlInput.trim()
    val urlValid = urlInput.startsWith("http://") || urlInput.startsWith("https://")
    val isDuplicate = urlInput.isNotEmpty() &&
        state.repositories.any { it.baseUrl == urlInput && it.id != state.editingRepository?.id }
    val showUrlError = urlInput.isNotEmpty() && !urlValid
    val canSave = state.nameInput.isNotBlank() && urlValid && !isDuplicate

    AlertDialog(
        onDismissRequest = { onEvent(RepositoryConfigEvent.DismissDialog) },
        title = {
            Text(stringResource(R.string.repository_add))
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = state.nameInput,
                    onValueChange = { onEvent(RepositoryConfigEvent.NameChanged(it)) },
                    label = { Text(stringResource(R.string.repository_name)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("repository-name-input"),
                )
                OutlinedTextField(
                    value = state.urlInput,
                    onValueChange = { onEvent(RepositoryConfigEvent.UrlChanged(it)) },
                    label = { Text(stringResource(R.string.repository_url)) },
                    singleLine = true,
                    isError = showUrlError || isDuplicate,
                    supportingText = {
                        when {
                            showUrlError -> Text(stringResource(R.string.repository_url_invalid))
                            isDuplicate -> Text(stringResource(R.string.repository_duplicate))
                            else -> Unit
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("repository-url-input"),
                )
                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = !typeExpanded },
                ) {
                    OutlinedTextField(
                        value = state.typeInput.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.repository_type)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true),
                    )
                    ExposedDropdownMenu(
                        expanded = typeExpanded,
                        onDismissRequest = { typeExpanded = false },
                    ) {
                        typeOptions.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.name) },
                                onClick = {
                                    onEvent(RepositoryConfigEvent.TypeChanged(type))
                                    typeExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onEvent(RepositoryConfigEvent.Save) },
                enabled = canSave,
                modifier = Modifier.testTag("repository-save"),
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(RepositoryConfigEvent.DismissDialog) }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
internal fun RepositoryConfigContentLightPreview() {
    MaterialTheme {
        RepositoryConfigContent(
            state = previewState(),
            onEvent = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111113)
@Composable
internal fun RepositoryConfigContentDarkPreview() {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(),
    ) {
        RepositoryConfigContent(
            state = previewState(),
            onEvent = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
internal fun RepositoryEditDialogLightPreview() {
    MaterialTheme {
        RepositoryEditDialog(
            state = RepositoryConfigUiState(
                showAddDialog = true,
                nameInput = "Mirror",
                urlInput = "https://hf-mirror.com",
            ),
            onEvent = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111113)
@Composable
internal fun RepositoryEditDialogDarkPreview() {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(),
    ) {
        RepositoryEditDialog(
            state = RepositoryConfigUiState(
                showAddDialog = true,
                nameInput = "Mirror",
                urlInput = "ftp://example.com",
                repositories = previewState().repositories,
            ),
            onEvent = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
internal fun RepositoryConfigEmptyLightPreview() {
    MaterialTheme {
        RepositoryConfigContent(
            state = RepositoryConfigUiState(),
            onEvent = {},
        )
    }
}

private fun previewState(): RepositoryConfigUiState = RepositoryConfigUiState(
    repositories = listOf(
        RepositoryConfig(
            id = "demo-1",
            name = "Hugging Face",
            baseUrl = "https://huggingface.co",
            enabled = true,
            type = RepositoryType.HUGGINGFACE,
        ),
        RepositoryConfig(
            id = "demo-2",
            name = "Mirror",
            baseUrl = "https://hf-mirror.com",
            enabled = false,
            type = RepositoryType.JSON_INDEX,
        ),
    ),
)
