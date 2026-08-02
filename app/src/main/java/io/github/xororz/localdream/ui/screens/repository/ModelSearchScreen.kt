package io.github.xororz.localdream.ui.screens.repository

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.GenerationPreferences
import io.github.xororz.localdream.modelcatalog.CatalogArtifactKind
import io.github.xororz.localdream.modelcatalog.CatalogDeviceCompatibility
import io.github.xororz.localdream.modelcatalog.HuggingFaceModelCatalogClient
import io.github.xororz.localdream.modelcatalog.ModelCatalogSearchResult
import io.github.xororz.localdream.modelcatalog.MultiRepositorySearchClient
import io.github.xororz.localdream.modelcatalog.MultiRepositorySearchStatus
import io.github.xororz.localdream.service.ModelDownloadService
import io.github.xororz.localdream.utils.Http
import kotlinx.coroutines.launch

/**
 * Host screen that makes [ModelSearchContent] reachable from the model list and
 * owns the search lifecycle and model installation. It builds a
 * [MultiRepositorySearchClient] from the built-in Hugging Face catalog plus any
 * enabled custom repositories, mirrors the installation flow used by the legacy
 * search dialog (foreground [ModelDownloadService]), and provides back
 * navigation.
 */
@Composable
fun ModelSearchScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloadState by ModelDownloadService.downloadState.collectAsStateWithLifecycle()

    var state by remember { mutableStateOf(ModelSearchUiState()) }
    var confirmDownload by remember { mutableStateOf<ModelCatalogSearchResult?>(null) }
    var alreadyInstalled by remember { mutableStateOf<ModelCatalogSearchResult?>(null) }
    var activeModelId by remember { mutableStateOf<String?>(null) }
    var handledSuccessId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val unknownErrorMessage = stringResource(R.string.unknown_error)
    val cannotDownloadMessage = stringResource(R.string.cannot_download_hint)

    BackHandler { navController.popBackStack() }

    fun search() {
        val query = state.query.trim()
        if (query.isEmpty() || state.status == SearchStatus.SEARCHING) return
        state = state.copy(status = SearchStatus.SEARCHING)
        scope.launch {
            try {
                val prefs = GenerationPreferences(context)
                val baseUrl = prefs.getBaseUrl()
                val customRepos = prefs.getCustomRepositories().filter { it.enabled }
                val client = MultiRepositorySearchClient(
                    builtInClient = HuggingFaceModelCatalogClient(baseUrl, Http.client),
                    customRepositories = customRepos,
                )
                val result = client.searchCompatible(query)
                val compatible = result.results.filter {
                    CatalogDeviceCompatibility.isCurrentDeviceCompatible(it.installExpectation())
                }
                val status = when (result.status) {
                    MultiRepositorySearchStatus.SUCCESS ->
                        if (compatible.isEmpty()) SearchStatus.EMPTY else SearchStatus.SUCCESS

                    MultiRepositorySearchStatus.PARTIAL_FAILURE ->
                        if (compatible.isEmpty()) SearchStatus.EMPTY else SearchStatus.PARTIAL_FAILURE

                    MultiRepositorySearchStatus.ALL_FAILED -> SearchStatus.ALL_FAILED

                    MultiRepositorySearchStatus.NO_REPOSITORIES ->
                        if (compatible.isEmpty()) SearchStatus.EMPTY else SearchStatus.SUCCESS
                }
                val errors = result.perRepositoryErrors
                    .mapNotNull { (id, throwable) -> id?.let { it to (throwable.message ?: unknownErrorMessage) } }
                    .toMap()
                state = state.copy(results = compatible, status = status, repositoryErrors = errors)
            } catch (e: Exception) {
                error = e.message ?: unknownErrorMessage
                state = state.copy(
                    status = SearchStatus.ALL_FAILED,
                    results = emptyList(),
                    repositoryErrors = emptyMap(),
                )
            }
        }
    }

    LaunchedEffect(downloadState, activeModelId) {
        val current = downloadState
        when {
            current is ModelDownloadService.DownloadState.Success &&
                current.modelId == activeModelId &&
                handledSuccessId != current.modelId -> {
                handledSuccessId = current.modelId
                activeModelId = null
                confirmDownload = null
            }

            current is ModelDownloadService.DownloadState.AlreadyInstalled &&
                current.modelId == activeModelId -> {
                alreadyInstalled = state.results.firstOrNull { it.localModelId == current.modelId }
                    ?: confirmDownload
                activeModelId = null
            }

            current is ModelDownloadService.DownloadState.Cancelled &&
                current.modelId == activeModelId -> {
                activeModelId = null
            }

            current is ModelDownloadService.DownloadState.Error &&
                current.modelId == activeModelId -> {
                error = current.message
                activeModelId = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_models)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        ModelSearchContent(
            state = state,
            onEvent = { event ->
                when (event) {
                    is ModelSearchEvent.QueryChanged -> state = state.copy(query = event.value)

                    ModelSearchEvent.Search -> search()

                    ModelSearchEvent.LoadMore -> {
                        // Paging is not supported by the multi-repository client yet.
                    }

                    ModelSearchEvent.RetryFailed -> search()

                    is ModelSearchEvent.InstallModel -> confirmDownload = event.result
                }
            },
            modifier = Modifier.padding(innerPadding),
        )
    }

    confirmDownload?.let { result ->
        AlertDialog(
            onDismissRequest = { confirmDownload = null },
            text = {
                Text(stringResource(R.string.search_model_install_confirm, result.displayName))
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
                Text(stringResource(R.string.search_model_installed_hint, result.displayName))
            },
            confirmButton = {
                TextButton(onClick = { alreadyInstalled = null }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }
}
