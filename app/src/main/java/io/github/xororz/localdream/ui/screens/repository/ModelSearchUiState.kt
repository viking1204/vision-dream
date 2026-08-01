package io.github.xororz.localdream.ui.screens.repository

import androidx.compose.runtime.Immutable
import io.github.xororz.localdream.modelcatalog.ModelCatalogSearchResult

enum class SearchStatus { IDLE, SEARCHING, SUCCESS, PARTIAL_FAILURE, ALL_FAILED, EMPTY }

@Immutable
data class ModelSearchUiState(
    val query: String = "",
    val status: SearchStatus = SearchStatus.IDLE,
    val results: List<ModelCatalogSearchResult> = emptyList(),
    val repositoryErrors: Map<String, String> = emptyMap(),
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
)
