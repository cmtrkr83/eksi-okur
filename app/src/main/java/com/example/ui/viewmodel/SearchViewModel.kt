package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.SearchHistoryEntity
import com.example.data.model.AutocompleteResult
import com.example.data.model.TopicItem
import com.example.data.model.UiResult
import com.example.data.repository.EksiRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val autocomplete: AutocompleteResult = AutocompleteResult(),
    val isAutocompleteLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val searchResult: UiResult<List<TopicItem>>? = null
)

class SearchViewModel(
    private val repository: EksiRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    val searchHistory: StateFlow<List<SearchHistoryEntity>> = repository.searchHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var autocompleteJob: Job? = null

    fun onQueryChanged(newQuery: String) {
        _uiState.update {
            it.copy(
                query = newQuery,
                hasSearched = if (newQuery.isBlank()) false else it.hasSearched,
                searchResult = if (newQuery.isBlank()) null else it.searchResult
            )
        }
        autocompleteJob?.cancel()
        if (newQuery.trim().length < 2) {
            _uiState.update { it.copy(autocomplete = AutocompleteResult(), isAutocompleteLoading = false) }
            return
        }
        autocompleteJob = viewModelScope.launch {
            delay(280)
            _uiState.update { it.copy(isAutocompleteLoading = true) }
            val result = repository.getAutocomplete(newQuery)
            _uiState.update { it.copy(autocomplete = result, isAutocompleteLoading = false) }
        }
    }

    fun submitSearch(overrideQuery: String? = null) {
        val q = (overrideQuery ?: _uiState.value.query).trim()
        if (q.isBlank()) return
        autocompleteJob?.cancel()
        _uiState.update {
            it.copy(
                query = q,
                hasSearched = true,
                searchResult = UiResult.Loading
            )
        }
        viewModelScope.launch {
            val res = repository.search(q)
            _uiState.update { it.copy(searchResult = res) }
        }
    }

    fun deleteHistoryItem(query: String) {
        viewModelScope.launch { repository.deleteSearchQuery(query) }
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clearSearchHistory() }
    }
}
