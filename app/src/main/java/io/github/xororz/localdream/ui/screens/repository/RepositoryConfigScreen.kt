package io.github.xororz.localdream.ui.screens.repository

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.github.xororz.localdream.data.GenerationPreferences
import io.github.xororz.localdream.modelcatalog.RepositoryConfig
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Host screen that wires [RepositoryConfigContent] into the app navigation and
 * owns persistence of the user-configured model repositories. The content stays
 * a pure UI surface; this screen supplies the [RepositoryConfigUiState], routes
 * every [RepositoryConfigEvent] to [GenerationPreferences], and provides back
 * navigation so the screen is reachable from [StudioHomeScreen].
 */
@Composable
fun RepositoryConfigScreen(navController: NavController) {
    val context = LocalContext.current
    val preferences = remember { GenerationPreferences(context) }
    val scope = rememberCoroutineScope()

    val repositories by remember { preferences.observeCustomRepositories() }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var state by remember { mutableStateOf(RepositoryConfigUiState(repositories = repositories)) }

    // Keep the content's repository list in sync with the persisted source of truth.
    LaunchedEffect(repositories) {
        state = state.copy(repositories = repositories)
    }

    BackHandler { navController.popBackStack() }

    val onEvent: (RepositoryConfigEvent) -> Unit = { event ->
        when (event) {
            RepositoryConfigEvent.ShowAddDialog -> {
                state = state.copy(showAddDialog = true)
            }

            RepositoryConfigEvent.DismissDialog -> {
                state = state.copy(
                    showAddDialog = false,
                    editingRepository = null,
                    nameInput = "",
                    urlInput = "",
                    urlError = null,
                    duplicateError = false,
                )
            }

            is RepositoryConfigEvent.StartEdit -> {
                state = state.copy(
                    editingRepository = event.repository,
                    nameInput = event.repository.name,
                    urlInput = event.repository.baseUrl,
                    typeInput = event.repository.type,
                    showAddDialog = true,
                )
            }

            is RepositoryConfigEvent.NameChanged -> {
                state = state.copy(nameInput = event.value)
            }

            is RepositoryConfigEvent.UrlChanged -> {
                state = state.copy(urlInput = event.value)
            }

            is RepositoryConfigEvent.TypeChanged -> {
                state = state.copy(typeInput = event.value)
            }

            RepositoryConfigEvent.Save -> {
                val url = state.urlInput.trim()
                val urlValid = url.startsWith("http://") || url.startsWith("https://")
                val isDuplicate = repositories.any {
                    it.baseUrl == url && it.id != state.editingRepository?.id
                }
                if (state.nameInput.isNotBlank() && urlValid && !isDuplicate) {
                    val updated = if (state.editingRepository != null) {
                        state.editingRepository!!.copy(
                            name = state.nameInput.trim(),
                            baseUrl = url,
                            type = state.typeInput,
                        )
                    } else {
                        RepositoryConfig(
                            id = UUID.randomUUID().toString(),
                            name = state.nameInput.trim(),
                            baseUrl = url,
                            type = state.typeInput,
                        )
                    }

                    val newList = if (state.editingRepository != null) {
                        repositories.map { if (it.id == updated.id) updated else it }
                    } else {
                        repositories + updated
                    }
                    scope.launch { preferences.saveCustomRepositories(newList) }

                    state = state.copy(
                        showAddDialog = false,
                        editingRepository = null,
                        nameInput = "",
                        urlInput = "",
                        urlError = null,
                        duplicateError = false,
                    )
                }
            }

            is RepositoryConfigEvent.ToggleEnabled -> {
                val newList = repositories.map {
                    if (it.id == event.repository.id) it.copy(enabled = !it.enabled) else it
                }
                scope.launch { preferences.saveCustomRepositories(newList) }
            }

            is RepositoryConfigEvent.Delete -> {
                val newList = repositories.filter { it.id != event.repository.id }
                scope.launch { preferences.saveCustomRepositories(newList) }
            }
        }
    }

    RepositoryConfigContent(
        state = state,
        onEvent = onEvent,
        onBack = { navController.popBackStack() },
    )
}
