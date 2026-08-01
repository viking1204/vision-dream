package io.github.xororz.localdream.ui.screens.repository

import io.github.xororz.localdream.modelcatalog.ModelCatalogSearchResult

sealed interface ModelSearchEvent {
    data class QueryChanged(val value: String) : ModelSearchEvent
    data object Search : ModelSearchEvent
    data object LoadMore : ModelSearchEvent
    data object RetryFailed : ModelSearchEvent
    data class InstallModel(val result: ModelCatalogSearchResult) : ModelSearchEvent
}
