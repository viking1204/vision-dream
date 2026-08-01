package io.github.xororz.localdream.ui.screens.repository

import androidx.compose.runtime.Immutable
import io.github.xororz.localdream.modelcatalog.RepositoryConfig
import io.github.xororz.localdream.modelcatalog.RepositoryType

@Immutable
data class RepositoryConfigUiState(
    val repositories: List<RepositoryConfig> = emptyList(),
    val showAddDialog: Boolean = false,
    val editingRepository: RepositoryConfig? = null,
    val nameInput: String = "",
    val urlInput: String = "",
    val typeInput: RepositoryType = RepositoryType.HUGGINGFACE,
    val urlError: String? = null,
    val duplicateError: Boolean = false,
)
