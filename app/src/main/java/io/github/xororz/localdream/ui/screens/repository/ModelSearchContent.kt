package io.github.xororz.localdream.ui.screens.repository

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R
import io.github.xororz.localdream.modelcatalog.CatalogArtifactKind
import io.github.xororz.localdream.modelcatalog.CatalogBackendHint
import io.github.xororz.localdream.modelcatalog.ModelCatalogSearchResult

private const val MAX_QUERY_CHARACTERS = 100

/**
 * Pure UI surface for multi-repository model search. All mutations are routed
 * through [onEvent] so the hosting screen owns the search client and lifecycle;
 * the composable only renders [state] and emits intents.
 *
 * Status mapping (see `SearchStatus`):
 *  - IDLE: hint text inviting the user to type a keyword.
 *  - SEARCHING: [ContainedLoadingIndicator] centered in the status area.
 *  - SUCCESS / PARTIAL_FAILURE: a [LazyColumn] of [ModelSearchResultCard]s.
 *    PARTIAL_FAILURE additionally lists each failed repository with a retry
 *    affordance above the results.
 *  - ALL_FAILED: a full-area error with a tap-to-retry affordance.
 *  - EMPTY: a "no matching models" message.
 */
@Composable
fun ModelSearchContent(
    state: ModelSearchUiState,
    onEvent: (ModelSearchEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("model-search-content"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = { onEvent(ModelSearchEvent.QueryChanged(it.take(MAX_QUERY_CHARACTERS))) },
            singleLine = true,
            label = { Text(stringResource(R.string.model_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                if (state.query.isNotBlank()) onEvent(ModelSearchEvent.Search)
            }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("model-search-input"),
        )

        when (state.status) {
            SearchStatus.IDLE -> IdleHint()
            SearchStatus.SEARCHING -> SearchingStatus()
            SearchStatus.ALL_FAILED -> AllFailedStatus(onRetry = { onEvent(ModelSearchEvent.RetryFailed) })
            SearchStatus.EMPTY -> EmptyStatus()
            SearchStatus.SUCCESS -> ResultsList(
                state = state,
                onInstall = { onEvent(ModelSearchEvent.InstallModel(it)) },
                onLoadMore = { onEvent(ModelSearchEvent.LoadMore) },
            )
            SearchStatus.PARTIAL_FAILURE -> Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.repositoryErrors.forEach { (repositoryId, _) ->
                    RepoFailureRow(
                        repositoryId = repositoryId,
                        onRetry = { onEvent(ModelSearchEvent.RetryFailed) },
                    )
                }
                ResultsList(
                    state = state,
                    onInstall = { onEvent(ModelSearchEvent.InstallModel(it)) },
                    onLoadMore = { onEvent(ModelSearchEvent.LoadMore) },
                )
            }
        }
    }
}

@Composable
private fun IdleHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.model_search_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchingStatus() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ContainedLoadingIndicator()
            Text(
                stringResource(R.string.search),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AllFailedStatus(onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 48.dp)
            .testTag("model-search-retry"),
        contentAlignment = Alignment.Center,
    ) {
        TextButton(onClick = onRetry) {
            Text(
                stringResource(R.string.model_search_failed),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun EmptyStatus() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.model_search_no_results),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RepoFailureRow(
    repositoryId: String,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    repositoryId,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.model_search_repo_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.migration_retry))
            }
        }
    }
}

@Composable
private fun ResultsList(
    state: ModelSearchUiState,
    onInstall: (ModelCatalogSearchResult) -> Unit,
    onLoadMore: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = state.results,
            key = { "${it.repositoryId}:${it.artifactFileName}:${it.localModelId}" },
        ) { result ->
            ModelSearchResultCard(
                result = result,
                onInstall = { onInstall(result) },
            )
        }
        if (state.hasMore) {
            item(key = "load-more") {
                OutlinedButton(
                    onClick = onLoadMore,
                    enabled = !state.loadingMore,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("model-search-load-more"),
                ) {
                    Text(stringResource(R.string.model_search_load_more))
                }
            }
        }
    }
}

@Composable
private fun ModelSearchResultCard(
    result: ModelCatalogSearchResult,
    onInstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Download, contentDescription = null)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    result.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    result.repositoryId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                result.backendType?.let { backend ->
                    AssistChip(
                        onClick = {},
                        label = { Text(backend) },
                        border = null,
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    )
                }
                result.sizeBytes?.let {
                    Text(
                        formatSearchBytes(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(
                onClick = onInstall,
                modifier = Modifier.testTag("model-search-install-${result.localModelId}"),
            ) {
                Text(stringResource(R.string.model_search_install))
                Spacer(Modifier.size(4.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
        }
    }
}

private fun formatSearchBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kib = bytes / 1024.0
    if (kib < 1024) return "%.1f KiB".format(kib)
    val mib = kib / 1024.0
    if (mib < 1024) return "%.1f MiB".format(mib)
    return "%.2f GiB".format(mib / 1024.0)
}

@Preview(showBackground = true)
@Composable
internal fun ModelSearchContentIdleLightPreview() {
    MaterialTheme {
        ModelSearchContent(
            state = previewState(status = SearchStatus.IDLE),
            onEvent = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111113)
@Composable
internal fun ModelSearchContentIdleDarkPreview() {
    MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
        ModelSearchContent(
            state = previewState(status = SearchStatus.IDLE),
            onEvent = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
internal fun ModelSearchContentSearchingLightPreview() {
    MaterialTheme {
        ModelSearchContent(
            state = previewState(status = SearchStatus.SEARCHING, query = "anime"),
            onEvent = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
internal fun ModelSearchContentSuccessLightPreview() {
    MaterialTheme {
        ModelSearchContent(
            state = previewState(
                status = SearchStatus.SUCCESS,
                query = "anime",
                results = previewResults(),
                hasMore = true,
            ),
            onEvent = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111113)
@Composable
internal fun ModelSearchContentSuccessDarkPreview() {
    MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
        ModelSearchContent(
            state = previewState(
                status = SearchStatus.SUCCESS,
                query = "anime",
                results = previewResults(),
                hasMore = true,
            ),
            onEvent = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
internal fun ModelSearchContentPartialFailureLightPreview() {
    MaterialTheme {
        ModelSearchContent(
            state = previewState(
                status = SearchStatus.PARTIAL_FAILURE,
                query = "anime",
                results = previewResults().take(1),
                repositoryErrors = mapOf("mirror-repo" to "503 Service Unavailable"),
            ),
            onEvent = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
internal fun ModelSearchContentAllFailedLightPreview() {
    MaterialTheme {
        ModelSearchContent(
            state = previewState(
                status = SearchStatus.ALL_FAILED,
                query = "anime",
                repositoryErrors = mapOf(
                    "builtin" to "timeout",
                    "mirror-repo" to "503",
                ),
            ),
            onEvent = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
internal fun ModelSearchContentEmptyLightPreview() {
    MaterialTheme {
        ModelSearchContent(
            state = previewState(status = SearchStatus.EMPTY, query = "zzz-nothing"),
            onEvent = {},
        )
    }
}

private fun previewState(
    status: SearchStatus,
    query: String = "",
    results: List<ModelCatalogSearchResult> = emptyList(),
    repositoryErrors: Map<String, String> = emptyMap(),
    hasMore: Boolean = false,
): ModelSearchUiState = ModelSearchUiState(
    query = query,
    status = status,
    results = results,
    repositoryErrors = repositoryErrors,
    hasMore = hasMore,
)

private fun previewResults(): List<ModelCatalogSearchResult> = listOf(
    ModelCatalogSearchResult(
        repositoryId = "xororz/anything-v5",
        localModelId = "xororz/anything-v5",
        displayName = "AnythingV5",
        artifactFileName = "anything-v5.zip",
        downloadUrl = "https://huggingface.co/xororz/anything-v5/resolve/main/anything-v5.zip",
        artifactKind = CatalogArtifactKind.LOCAL_DREAM_ZIP,
        backendHint = CatalogBackendHint.QNN_NPU,
        backendType = "QNN",
        hardwareTarget = null,
        sizeBytes = 1_073_741_824L,
        lastModified = null,
    ),
    ModelCatalogSearchResult(
        repositoryId = "xororz/cyberrealistic",
        localModelId = "xororz/cyberrealistic",
        displayName = "CyberRealistic",
        artifactFileName = "cyber.safetensors",
        downloadUrl = "https://huggingface.co/xororz/cyberrealistic/resolve/main/cyber.safetensors",
        artifactKind = CatalogArtifactKind.SD15_SAFETENSORS,
        backendHint = CatalogBackendHint.SD15_CONVERSION,
        backendType = "MNN",
        hardwareTarget = null,
        sizeBytes = 2_097_152_000L,
        lastModified = null,
    ),
)
