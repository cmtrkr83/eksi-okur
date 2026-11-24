package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.DEFAULT_EKSI_CHANNELS
import com.example.data.model.EksiChannel
import com.example.data.model.FeedCategory
import com.example.data.model.TopicItem
import com.example.data.model.UiResult
import com.example.data.repository.EksiRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FeedUiState(
    val category: FeedCategory = FeedCategory.GUNDEM,
    val selectedChannel: EksiChannel = DEFAULT_EKSI_CHANNELS.first(),
    val page: Int = 1,
    val isRefreshing: Boolean = false,
    val topicsResult: UiResult<List<TopicItem>> = UiResult.Loading,
    val activeDetailSlug: String? = null,
    val activeDetailEntryId: Long? = null
)

class FeedViewModel(
    private val repository: EksiRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    init {
        loadFeed()
    }

    fun selectCategory(category: FeedCategory) {
        if (_uiState.value.category == category) return
        _uiState.update { it.copy(category = category, page = 1) }
        loadFeed()
    }

    fun selectChannel(channel: EksiChannel) {
        _uiState.update {
            it.copy(
                category = FeedCategory.KANAL,
                selectedChannel = channel,
                page = 1
            )
        }
        loadFeed()
    }

    fun changePage(newPage: Int) {
        val clamped = newPage.coerceAtLeast(1)
        if (clamped == _uiState.value.page) return
        _uiState.update { it.copy(page = clamped) }
        loadFeed()
    }

    fun refresh() {
        loadFeed(isPullRefresh = true)
    }

    fun setActiveDetail(slugUrl: String?, entryId: Long? = null) {
        _uiState.update { it.copy(activeDetailSlug = slugUrl, activeDetailEntryId = entryId) }
    }

    fun loadFeed(isPullRefresh: Boolean = false) {
        viewModelScope.launch {
            val current = _uiState.value
            _uiState.update {
                if (isPullRefresh) {
                    it.copy(isRefreshing = true)
                } else {
                    it.copy(topicsResult = UiResult.Loading)
                }
            }

            val result = repository.getFeed(
                category = current.category,
                channelSlug = current.selectedChannel.slug,
                page = current.page
            )

            _uiState.update {
                it.copy(
                    isRefreshing = false,
                    topicsResult = result
                )
            }
        }
    }
}
