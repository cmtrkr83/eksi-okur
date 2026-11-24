package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.UserPreferences
import com.example.data.model.EntryItem
import com.example.data.model.TopicDetail
import com.example.data.model.TopicFilter
import com.example.data.model.UiResult
import com.example.data.repository.EksiRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TopicUiState(
    val requestedSlugOrUrl: String = "",
    val singleEntryId: Long? = null,
    val currentPage: Int = 1,
    val filter: TopicFilter = TopicFilter.ALL,
    val isRefreshing: Boolean = false,
    val result: UiResult<TopicDetail> = UiResult.Loading,
    val actionMessage: String? = null,
    val isComposeOpen: Boolean = false,
    val isSubmittingEntry: Boolean = false,
    val submitEntryError: String? = null,
    val showLoginPromptForEntry: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class TopicViewModel(
    private val repository: EksiRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TopicUiState())
    val uiState: StateFlow<TopicUiState> = _uiState.asStateFlow()

    val preferences: StateFlow<UserPreferences> = repository.userPreferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())

    val bookmarkedEntryIds: StateFlow<Set<Long>> = repository.bookmarks
        .map { list -> list.mapNotNull { it.entryId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val isTopicBookmarked: StateFlow<Boolean> = _uiState
        .map { state ->
            val detail = (state.result as? UiResult.Success)?.data
                ?: (state.result as? UiResult.Error)?.cachedData
            detail?.canonicalUrl ?: state.requestedSlugOrUrl
        }
        .flatMapLatest { slug ->
            if (slug.isBlank()) flowOf(false) else repository.observeIsTopicBookmarked(slug)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun loadTopic(slugOrUrl: String, page: Int = 1, filter: TopicFilter = TopicFilter.ALL) {
        _uiState.update {
            it.copy(
                requestedSlugOrUrl = slugOrUrl,
                singleEntryId = null,
                currentPage = page.coerceAtLeast(1),
                filter = filter,
                result = UiResult.Loading
            )
        }
        fetchTopicInternal(isPullRefresh = false)
    }

    fun loadSingleEntry(entryId: Long) {
        _uiState.update {
            it.copy(
                requestedSlugOrUrl = "/entry/$entryId",
                singleEntryId = entryId,
                currentPage = 1,
                filter = TopicFilter.ALL,
                result = UiResult.Loading
            )
        }
        viewModelScope.launch {
            val res = repository.getSingleEntry(entryId)
            _uiState.update { it.copy(isRefreshing = false, result = res) }
        }
    }

    fun selectFilter(filter: TopicFilter) {
        if (_uiState.value.filter == filter && _uiState.value.singleEntryId == null) return
        val slug = currentCanonicalOrRequested()
        loadTopic(slug, page = 1, filter = filter)
    }

    fun goToPage(page: Int) {
        val current = _uiState.value
        val detail = (current.result as? UiResult.Success)?.data
        val maxPage = detail?.totalPages ?: Int.MAX_VALUE
        val target = page.coerceIn(1, maxPage.coerceAtLeast(1))
        if (target == current.currentPage && current.singleEntryId == null) return
        _uiState.update { it.copy(currentPage = target, singleEntryId = null) }
        fetchTopicInternal(isPullRefresh = false)
    }

    fun refresh() {
        val singleId = _uiState.value.singleEntryId
        if (singleId != null) {
            _uiState.update { it.copy(isRefreshing = true) }
            viewModelScope.launch {
                val res = repository.getSingleEntry(singleId)
                _uiState.update { it.copy(isRefreshing = false, result = res) }
            }
        } else {
            fetchTopicInternal(isPullRefresh = true)
        }
    }

    fun openFullTopicFromSingleEntry() {
        val detail = (_uiState.value.result as? UiResult.Success)?.data ?: return
        loadTopic(detail.canonicalUrl, page = 1, filter = TopicFilter.ALL)
    }

    fun onWriteEntryClicked() {
        val detail = (_uiState.value.result as? UiResult.Success)?.data
        val isLoggedIn = !preferences.value.loggedInNick.isNullOrBlank() ||
            detail?.entryFormAction != null
        if (isLoggedIn) {
            _uiState.update { it.copy(isComposeOpen = true, submitEntryError = null) }
        } else {
            viewModelScope.launch {
                val detected = repository.refreshSessionUser()
                if (detected != null) {
                    _uiState.update { it.copy(isComposeOpen = true, submitEntryError = null) }
                } else {
                    _uiState.update { it.copy(showLoginPromptForEntry = true) }
                }
            }
        }
    }

    fun dismissLoginPrompt() {
        _uiState.update { it.copy(showLoginPromptForEntry = false) }
    }

    fun closeComposeSheet() {
        if (_uiState.value.isSubmittingEntry) return
        _uiState.update { it.copy(isComposeOpen = false, submitEntryError = null) }
    }

    fun onWebAuthCompleted(detectedNick: String?, openComposeAfter: Boolean = false) {
        viewModelScope.launch {
            val nick = repository.refreshSessionUser(detectedNick)
            refresh()
            if (openComposeAfter && nick != null) {
                _uiState.update { it.copy(isComposeOpen = true, submitEntryError = null) }
            }
        }
    }

    fun submitEntry(content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(submitEntryError = "Entry metni boş olamaz.") }
            return
        }
        val detail = (_uiState.value.result as? UiResult.Success)?.data
            ?: (_uiState.value.result as? UiResult.Error)?.cachedData
            ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmittingEntry = true, submitEntryError = null) }
            val result = repository.postEntry(
                topicTitle = detail.title,
                topicId = detail.topicId,
                canonicalUrl = detail.canonicalUrl,
                content = trimmed,
                formAction = detail.entryFormAction,
                formFields = detail.entryFormFields
            )
            result.onSuccess {
                _uiState.update {
                    it.copy(
                        isSubmittingEntry = false,
                        isComposeOpen = false,
                        submitEntryError = null,
                        actionMessage = "Entry'niz yayınlandı!"
                    )
                }
                loadTopic(
                    slugOrUrl = detail.canonicalUrl,
                    page = detail.totalPages.coerceAtLeast(1),
                    filter = TopicFilter.ALL
                )
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSubmittingEntry = false,
                        submitEntryError = err.message ?: "Entry gönderilemedi."
                    )
                }
            }
        }
    }

    fun toggleTopicBookmark() {
        val detail = (_uiState.value.result as? UiResult.Success)?.data
            ?: (_uiState.value.result as? UiResult.Error)?.cachedData
            ?: return
        val currently = isTopicBookmarked.value
        viewModelScope.launch {
            repository.toggleTopicBookmark(
                title = detail.title,
                slugUrl = detail.canonicalUrl,
                currentlyBookmarked = currently
            )
            _uiState.update {
                it.copy(
                    actionMessage = if (currently) "Başlık yer imlerinden kaldırıldı"
                    else "Başlık yer imlerine kaydedildi"
                )
            }
        }
    }

    fun toggleEntryBookmark(entry: EntryItem) {
        val detail = (_uiState.value.result as? UiResult.Success)?.data
            ?: (_uiState.value.result as? UiResult.Error)?.cachedData
            ?: return
        val currently = bookmarkedEntryIds.value.contains(entry.id)
        viewModelScope.launch {
            repository.toggleEntryBookmark(
                topicTitle = detail.title,
                topicUrl = detail.canonicalUrl,
                entry = entry,
                currentlyBookmarked = currently
            )
            _uiState.update {
                it.copy(
                    actionMessage = if (currently) "Entry yer imlerinden kaldırıldı"
                    else "Entry yer imlerine kaydedildi"
                )
            }
        }
    }

    fun toggleFavorite(entry: EntryItem) {
        val detail = (_uiState.value.result as? UiResult.Success)?.data ?: return
        viewModelScope.launch {
            val res = repository.toggleFavorite(
                entryId = entry.id,
                currentlyFavorited = entry.isFavorited,
                verificationToken = detail.verificationToken
            )
            res.onSuccess { newCount ->
                val updatedEntries = detail.entries.map { item ->
                    if (item.id == entry.id) {
                        val updatedFav = !item.isFavorited
                        val resolvedCount = if (newCount >= 0) newCount else (item.favoriteCount + if (updatedFav) 1 else -1).coerceAtLeast(0)
                        item.copy(isFavorited = updatedFav, favoriteCount = resolvedCount)
                    } else item
                }
                _uiState.update {
                    it.copy(result = UiResult.Success(detail.copy(entries = updatedEntries)))
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(actionMessage = err.message ?: "Favori işlemi için giriş yapmalısınız.")
                }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(actionMessage = null) }
    }

    private fun currentCanonicalOrRequested(): String {
        val detail = (_uiState.value.result as? UiResult.Success)?.data
            ?: (_uiState.value.result as? UiResult.Error)?.cachedData
        return detail?.canonicalUrl?.takeIf { it.isNotBlank() } ?: _uiState.value.requestedSlugOrUrl
    }

    private fun fetchTopicInternal(isPullRefresh: Boolean) {
        viewModelScope.launch {
            val current = _uiState.value
            if (current.requestedSlugOrUrl.isBlank()) return@launch

            _uiState.update {
                if (isPullRefresh) it.copy(isRefreshing = true)
                else it.copy(result = UiResult.Loading)
            }

            val res = repository.getTopic(
                slugOrUrl = current.requestedSlugOrUrl,
                page = current.currentPage,
                filter = current.filter
            )

            _uiState.update { state ->
                val resolvedPage = (res as? UiResult.Success)?.data?.currentPage ?: state.currentPage
                state.copy(
                    isRefreshing = false,
                    currentPage = resolvedPage,
                    result = res
                )
            }
        }
    }
}
