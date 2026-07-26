package io.github.xororz.localdream.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.GenerationPreferences
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.data.ModelContentRating
import io.github.xororz.localdream.data.ModelRepository
import io.github.xororz.localdream.modelcatalog.CatalogArtifactKind
import io.github.xororz.localdream.modelcatalog.CatalogDeviceCompatibility
import io.github.xororz.localdream.modelcatalog.HuggingFaceModelCatalogClient
import io.github.xororz.localdream.modelcatalog.ModelCatalogSearchResult
import io.github.xororz.localdream.modelcatalog.ModelCompatibilityEvaluator
import io.github.xororz.localdream.service.ModelDownloadService
import io.github.xororz.localdream.utils.Http
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun ModelSearchDialog(
    onDismiss: () -> Unit,
    onInstalled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloadState by ModelDownloadService.downloadState.collectAsState()
    var keyword by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<ModelCatalogSearchResult>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDownload by remember { mutableStateOf<ModelCatalogSearchResult?>(null) }
    var alreadyInstalled by remember { mutableStateOf<ModelCatalogSearchResult?>(null) }
    var activeModelId by remember { mutableStateOf<String?>(null) }
    var handledSuccessId by remember { mutableStateOf<String?>(null) }
    val unknownErrorMessage = stringResource(R.string.unknown_error)
    val cannotDownloadMessage = stringResource(R.string.cannot_download_hint)

    fun search() {
        val query = keyword.trim()
        if (query.isEmpty() || searching) return
        searching = true
        searched = true
        error = null
        scope.launch {
            try {
                val baseUrl = GenerationPreferences(context).getBaseUrl()
                val reservedModelIds = ModelRepository.reservedModelIds()
                val evaluator = ModelCompatibilityEvaluator()
                results = HuggingFaceModelCatalogClient(baseUrl, Http.client)
                    .searchCompatible(query, evaluator)
                    .filter {
                        CatalogDeviceCompatibility.isCurrentDeviceCompatible(
                            it.installExpectation(),
                        )
                    }
                    // Keep installed built-ins visible so selecting the same
                    // official artifact produces the required duplicate
                    // warning. An uninstalled reserved ID stays hidden because
                    // catalog installation must never overwrite built-in
                    // identity/configuration.
                    .filter { result ->
                        result.localModelId !in reservedModelIds ||
                            isInstalledModelDirectory(context, result.localModelId)
                    }
            } catch (e: Exception) {
                error = e.message ?: unknownErrorMessage
                results = emptyList()
            } finally {
                searching = false
            }
        }
    }

    LaunchedEffect(downloadState, activeModelId) {
        val state = downloadState
        when {
            state is ModelDownloadService.DownloadState.Success &&
                state.modelId == activeModelId &&
                handledSuccessId != state.modelId -> {
                handledSuccessId = state.modelId
                activeModelId = null
                onInstalled()
            }

            state is ModelDownloadService.DownloadState.AlreadyInstalled &&
                state.modelId == activeModelId -> {
                results.firstOrNull { it.localModelId == state.modelId }?.let {
                    alreadyInstalled = it
                }
                activeModelId = null
            }

            state is ModelDownloadService.DownloadState.Cancelled &&
                state.modelId == activeModelId -> {
                activeModelId = null
            }

            state is ModelDownloadService.DownloadState.Error &&
                state.modelId == activeModelId -> {
                error = state.message
                activeModelId = null
            }
        }
    }

    confirmDownload?.let { result ->
        AlertDialog(
            onDismissRequest = { confirmDownload = null },
            text = {
                Text(
                    stringResource(
                        R.string.search_model_install_confirm,
                        result.displayName,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDownload = null
                        val current = downloadState
                        if (current.isBusy()) {
                            error = cannotDownloadMessage
                            return@TextButton
                        }
                        activeModelId = result.localModelId
                        handledSuccessId = null
                        val installKind = when (result.artifactKind) {
                            CatalogArtifactKind.LOCAL_DREAM_ZIP ->
                                ModelDownloadService.INSTALL_KIND_LOCAL_DREAM_ZIP

                            CatalogArtifactKind.LOCAL_DREAM_DIRECTORY ->
                                ModelDownloadService.INSTALL_KIND_LOCAL_DREAM_DIRECTORY

                            CatalogArtifactKind.SD15_SAFETENSORS ->
                                ModelDownloadService.INSTALL_KIND_SD15_CHECKPOINT
                        }
                        val intent = Intent(context, ModelDownloadService::class.java).apply {
                            action = ModelDownloadService.ACTION_START_DOWNLOAD
                            putExtra(ModelDownloadService.EXTRA_MODEL_ID, result.localModelId)
                            putExtra(ModelDownloadService.EXTRA_MODEL_NAME, result.displayName)
                            putExtra(ModelDownloadService.EXTRA_FILE_URL, result.downloadUrl)
                            putExtra(ModelDownloadService.EXTRA_MODEL_TYPE, "catalog")
                            putExtra(ModelDownloadService.EXTRA_CATALOG_INSTALL_KIND, installKind)
                            putExtra(
                                ModelDownloadService.EXTRA_CATALOG_INSTALL_EXPECTATION,
                                result.installExpectation().toJsonString(),
                            )
                            putExtra(
                                ModelDownloadService.EXTRA_MODEL_METADATA_JSON,
                                result.installationMetadata().toJsonString(),
                            )
                            result.downloadManifest?.let { manifest ->
                                putExtra(
                                    ModelDownloadService.EXTRA_CATALOG_DOWNLOAD_MANIFEST,
                                    manifest.toJsonString(),
                                )
                            }
                            result.sha256?.let {
                                putExtra(ModelDownloadService.EXTRA_EXPECTED_SHA256, it)
                            }
                            result.sizeBytes?.let {
                                putExtra(ModelDownloadService.EXTRA_EXPECTED_SIZE_BYTES, it)
                            }
                        }
                        try {
                            context.startForegroundService(intent)
                        } catch (e: RuntimeException) {
                            activeModelId = null
                            error = e.message ?: unknownErrorMessage
                        }
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDownload = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    alreadyInstalled?.let { result ->
        AlertDialog(
            onDismissRequest = { alreadyInstalled = null },
            title = { Text(stringResource(R.string.search_model_installed_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.search_model_installed_hint,
                        result.displayName,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { alreadyInstalled = null }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.search_models)) },
        text = {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp, max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.search_models_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it.take(MAX_KEYWORD_CHARACTERS) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.search_models_keyword)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { search() },
                    enabled = keyword.isNotBlank() && !searching,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (searching) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(stringResource(R.string.search))
                }
                error?.let {
                    Text(
                        stringResource(R.string.search_model_failed, it),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (searched && !searching && results.isEmpty() && error == null) {
                    Text(
                        stringResource(R.string.search_no_compatible_models),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = results,
                        key = {
                            "${it.repositoryId}:${it.artifactFileName}:${it.localModelId}"
                        },
                    ) { result ->
                        val installed =
                            isInstalledModelDirectory(context, result.localModelId)
                        ModelSearchResultCard(
                            result = result,
                            installed = installed,
                            busy = activeModelId != null || downloadState.isBusy(),
                            onClick = {
                                if (installed) {
                                    alreadyInstalled = result
                                } else {
                                    confirmDownload = result
                                }
                            },
                        )
                    }
                }
                activeDownloadLabel(downloadState)?.let { label ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (downloadState.isBusy()) {
                            TextButton(
                                onClick = {
                                    val modelId = downloadState.busyModelId() ?: return@TextButton
                                    val cancelIntent =
                                        Intent(context, ModelDownloadService::class.java).apply {
                                            action =
                                                ModelDownloadService.ACTION_CANCEL_DOWNLOAD
                                            putExtra(
                                                ModelDownloadService.EXTRA_MODEL_ID,
                                                modelId,
                                            )
                                        }
                                    context.startService(cancelIntent)
                                },
                            ) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
private fun ModelSearchResultCard(
    result: ModelCatalogSearchResult,
    installed: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        enabled = !busy,
        modifier = modifier.fillMaxWidth(),
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
            Icon(Icons.Default.CloudDownload, contentDescription = null)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(result.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.search_model_source, result.repositoryId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (result.artifactKind == CatalogArtifactKind.SD15_SAFETENSORS) {
                        stringResource(R.string.search_model_compatible_sd15)
                    } else {
                        stringResource(R.string.search_model_compatible_package)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (result.contentRating == ModelContentRating.NSFW) {
                    Text(
                        text = "NSFW",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                result.sizeBytes?.let {
                    Text(
                        formatCatalogBytes(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                if (installed) {
                    stringResource(R.string.downloaded)
                } else {
                    stringResource(R.string.download)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (installed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

@Composable
private fun activeDownloadLabel(
    state: ModelDownloadService.DownloadState,
): String? = when (state) {
    is ModelDownloadService.DownloadState.Downloading ->
        "${(state.progress * 100).toInt()}%"

    is ModelDownloadService.DownloadState.Extracting ->
        stringResource(R.string.extracting)

    is ModelDownloadService.DownloadState.Installing -> state.message

    is ModelDownloadService.DownloadState.Cancelled ->
        stringResource(R.string.download_cancelled)

    else -> null
}

private fun ModelDownloadService.DownloadState.busyModelId(): String? = when (this) {
    is ModelDownloadService.DownloadState.Downloading -> modelId
    is ModelDownloadService.DownloadState.Extracting -> modelId
    is ModelDownloadService.DownloadState.Installing -> modelId
    else -> null
}

private fun isInstalledModelDirectory(context: android.content.Context, modelId: String): Boolean {
    val directory = File(Model.getModelsDir(context), modelId)
    return directory.isDirectory && directory.listFiles()?.isNotEmpty() == true
}

private fun formatCatalogBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kib = bytes / 1024.0
    if (kib < 1024) return "%.1f KiB".format(kib)
    val mib = kib / 1024.0
    if (mib < 1024) return "%.1f MiB".format(mib)
    return "%.2f GiB".format(mib / 1024.0)
}

private const val MAX_KEYWORD_CHARACTERS = 100
