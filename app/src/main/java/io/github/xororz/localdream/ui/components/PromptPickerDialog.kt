package io.github.xororz.localdream.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.PromptRepository
import io.github.xororz.localdream.data.db.PromptTemplateEntity
import kotlinx.coroutines.launch

/**
 * Selects a persisted prompt pair and records its use before returning it.
 */
@Composable
fun PromptPickerDialog(
    onDismissRequest: () -> Unit,
    onTemplateSelected: (PromptTemplateEntity) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { PromptRepository(context) }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var selectingId by remember { mutableLongStateOf(NO_SELECTION) }
    val templates by remember(query) { repository.observeSearch(query) }
        .collectAsState(initial = emptyList())

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.prompt_picker_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.prompt_picker_search_hint)) },
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
                                        R.string.prompt_picker_clear_search,
                                    ),
                                )
                            }
                        }
                    },
                )

                if (templates.isEmpty()) {
                    Text(
                        text = stringResource(
                            if (query.isBlank()) {
                                R.string.prompt_picker_empty
                            } else {
                                R.string.prompt_picker_no_results
                            },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 440.dp),
                    ) {
                        items(
                            items = templates,
                            key = { it.id },
                            contentType = { "prompt_template" },
                        ) { template ->
                            PromptPickerRow(
                                template = template,
                                selecting = selectingId == template.id,
                                enabled = selectingId == NO_SELECTION,
                                onClick = {
                                    if (selectingId == NO_SELECTION) {
                                        selectingId = template.id
                                        scope.launch {
                                            runCatching {
                                                repository.markUsed(template.id)
                                            }
                                            selectingId = NO_SELECTION
                                            onTemplateSelected(template)
                                        }
                                    }
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun PromptPickerRow(
    template: PromptTemplateEntity,
    selecting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = template.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = template.prompt,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (template.negativePrompt.isNotBlank()) {
                    Row {
                        Text(
                            text = stringResource(R.string.prompt_picker_negative_prefix),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = template.negativePrompt,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        trailingContent = {
            if (selecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            }
        },
        modifier = Modifier.clickable(
            enabled = enabled,
            onClick = onClick,
        ),
    )
}

private const val NO_SELECTION = -1L
