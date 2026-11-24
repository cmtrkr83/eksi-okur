package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AuthorProfile
import com.example.data.model.AuthorTab
import com.example.data.model.UiResult
import com.example.data.repository.EksiRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthorUiState(
    val authorSlug: String = "",
    val activeTab: AuthorTab = AuthorTab.RECENT,
    val page: Int = 1,
    val result: UiResult<AuthorProfile> = UiResult.Loading
)

class AuthorViewModel(
    private val repository: EksiRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthorUiState())
    val uiState: StateFlow<AuthorUiState> = _uiState.asStateFlow()

    fun loadAuthor(authorSlug: String, tab: AuthorTab = AuthorTab.RECENT, page: Int = 1) {
        _uiState.update {
            it.copy(
                authorSlug = authorSlug,
                activeTab = tab,
                page = page,
                result = UiResult.Loading
            )
        }
        viewModelScope.launch {
            val res = repository.getAuthorProfile(authorSlug, tab, page)
            _uiState.update { it.copy(result = res) }
        }
    }

    fun selectTab(tab: AuthorTab) {
        val current = _uiState.value
        if (current.activeTab == tab) return
        loadAuthor(current.authorSlug, tab, 1)
    }

    fun goToPage(page: Int) {
        val current = _uiState.value
        loadAuthor(current.authorSlug, current.activeTab, page.coerceAtLeast(1))
    }

    fun refresh() {
        val current = _uiState.value
        loadAuthor(current.authorSlug, current.activeTab, current.page)
    }
}
