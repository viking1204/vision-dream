package io.github.xororz.localdream.ui.screens.repository

import io.github.xororz.localdream.modelcatalog.RepositoryConfig
import io.github.xororz.localdream.modelcatalog.RepositoryType

sealed interface RepositoryConfigEvent {
    data object ShowAddDialog : RepositoryConfigEvent
    data object DismissDialog : RepositoryConfigEvent
    data class StartEdit(val repository: RepositoryConfig) : RepositoryConfigEvent
    data class NameChanged(val value: String) : RepositoryConfigEvent
    data class UrlChanged(val value: String) : RepositoryConfigEvent
    data class TypeChanged(val value: RepositoryType) : RepositoryConfigEvent
    data object Save : RepositoryConfigEvent
    data class ToggleEnabled(val repository: RepositoryConfig) : RepositoryConfigEvent
    data class Delete(val repository: RepositoryConfig) : RepositoryConfigEvent
}
